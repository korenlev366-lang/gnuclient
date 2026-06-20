package gnu.client.module.modules.combat;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.ModeSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.mc.McAccess;
import gnu.client.runtime.packet.PacketEvents;
import gnu.client.runtime.packet.PacketHelper;
import gnu.client.runtime.packet.PacketListener;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * Hit Select — filters unnecessary clicks by managing attack timing windows
 * per target. Ported from raven-bS {@code HitSelect}.
 *
 * <p>Modes:
 * <ul>
 *   <li><b>Burst</b> — after landing a hit, block further clicks for a
 *       predicted hurt window (preventing over-swing while the server
 *       hasn't processed the next invincibility frame).</li>
 *   <li><b>Criticals</b> — only allow clicks when the player can land a
 *       critical hit (falling, not on ground, not in water, etc.).</li>
 * </ul>
 *
 * <p>Integrates as a passive filter: other modules (AutoClicker, KillAura)
 * or the native click handler call {@link #shouldBlockClick()} before
 * performing an attack. This module tracks per-target hurt-time state
 * via {@link #onTick()} and packet interception
 * ({@link PacketListener#onSend} for C02 ATTACK).
 */
public final class HitSelectModule extends Module implements PacketListener {

    // ────────────────────────────────────────────────────────────────
    // Constants
    // ────────────────────────────────────────────────────────────────

    private static final double HIT_RANGE = 3.0D;
    private static final double HIT_RANGE_SQ = HIT_RANGE * HIT_RANGE;
    private static final int HURT_WINDOW_TICKS = 10;
    private static final int SERVER_COOLDOWN_TICKS = HURT_WINDOW_TICKS;
    private static final int SERVER_TIMEOUT_TICKS = 30;

    // Bitmask flags for block reasons
    private static final int BLOCK_WAIT_FIRST      = 1;
    private static final int BLOCK_SERVER_COOLDOWN  = 1 << 3;
    private static final int BLOCK_PREDICTED_BURST  = 1 << 4;
    private static final int BLOCK_CRITICALS        = 1 << 5;

    // ────────────────────────────────────────────────────────────────
    // Settings
    // ────────────────────────────────────────────────────────────────

    private static final String[] MODES = { "Burst", "Criticals" };

    private final SliderSetting pauseDuration =
            addSetting(new SliderSetting("Pause duration", 500.0f, 0.0f, 500.0f));
    private final ModeSetting mode =
            addSetting(new ModeSetting("Mode", 0, Arrays.asList(MODES)));
    private final SliderSetting waitForFirstHit =
            addSetting(new SliderSetting("Wait for first hit", 0.0f, 0.0f, 500.0f));
    private final BoolSetting disableDuringKnockback =
            addSetting(new BoolSetting("Disable during knockback", false));
    private final BoolSetting onlyWhileDamaged =
            addSetting(new BoolSetting("Only while damaged", false));
    private final BoolSetting useServerAttackTime =
            addSetting(new BoolSetting("Use server attack time", false));
    private final BoolSetting fakeSwing =
            addSetting(new BoolSetting("Fake swing", false));
    private final SliderSetting inCombatCancelRate =
            addSetting(new SliderSetting("In combat", 100.0f, 0.0f, 100.0f));
    private final SliderSetting missedSwingsCancelRate =
            addSetting(new SliderSetting("Missed swings", 100.0f, 0.0f, 100.0f));

    // ────────────────────────────────────────────────────────────────
    // Runtime state
    // ────────────────────────────────────────────────────────────────

    private final Random random = new Random();

    // Per-entity-id tracking
    private final Map<Integer, TargetState> targetStates = new HashMap<>();

    // Current target (nearest valid player in range)
    private Object currentTarget;
    private int currentTargetId = -1;

    // Self-damage tracking
    private int lastSelfHurtTime;
    private boolean takingKnockback;

    // Wait-for-first-hit delay
    private boolean waitFirstTracking;
    private int waitFirstStartTick = -1;
    private boolean waitFirstUnlocked;

    // Tick counter (monotonic, wraps safely)
    private int tickCounter;

    // ────────────────────────────────────────────────────────────────
    // Construction
    // ────────────────────────────────────────────────────────────────

    public HitSelectModule() {
        super("Hit Select", "Filters unnecessary clicks by managing attack timing per target",
                Category.COMBAT);
    }

    // ────────────────────────────────────────────────────────────────
    // Module lifecycle
    // ────────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        tickCounter = 0;
        resetAllState();
        PacketEvents.register(this);
    }

    @Override
    public void onDisable() {
        PacketEvents.unregister(this);
        resetAllState();
    }

    // ────────────────────────────────────────────────────────────────
    // Static API (called by AutoClicker / KillAura before attacking)
    // ────────────────────────────────────────────────────────────────

    /**
     * @return true if the current click should be blocked (filtered out).
     */
    public static boolean shouldBlockClick() {
        Module mod = ModuleManager.INSTANCE.getModule("Hit Select");
        if (!(mod instanceof HitSelectModule) || !mod.isEnabled())
            return false;
        return ((HitSelectModule) mod).internalShouldBlock();
    }

    // ────────────────────────────────────────────────────────────────
    // Tick / event handlers
    // ────────────────────────────────────────────────────────────────

    @Override
    public void onTick() {
        tickCounter++;
        int currentTick = tickCounter;

        Object player = McAccess.thePlayer();
        Object world = McAccess.theWorld();
        if (player == null || world == null) {
            resetAllState();
            return;
        }

        pruneTargetStates(world);

        // Find current target (nearest valid player in HIT_RANGE)
        Object nextTarget = findNearestTarget(player, world);
        updateCurrentTarget(nextTarget, player, currentTick);
        updateSelfDamage(player, currentTick);
        updateTargetDamage(currentTick);
    }

    // ────────────────────────────────────────────────────────────────
    // PacketListener
    // ────────────────────────────────────────────────────────────────

    @Override
    public boolean onSend(Object packet) {
        // Track outgoing C02 ATTACK to hint at pending server confirmation
        if (!PacketHelper.isAttackUseEntity(packet))
            return false;

        int currentTick = tickCounter;

        int targetId = PacketHelper.packetEntityIdInWorld(
                packet, McAccess.theWorld());
        if (targetId < 0)
            return false;

        // If useServerAttackTime, start pending-server-confirmation window
        if (useServerAttackTime.getValue()) {
            TargetState state = getOrCreateTargetState(targetId);
            state.pendingServerConfirmationTick = currentTick;
            state.lastConfirmedTargetDamageTick = -1;
        }

        return false;
    }

    @Override
    public boolean onReceive(Object packet) {
        return false;
    }

    // ────────────────────────────────────────────────────────────────
    // Internal click filter
    // ────────────────────────────────────────────────────────────────

    private boolean internalShouldBlock() {
        Object player = McAccess.thePlayer();
        Object world = McAccess.theWorld();
        if (player == null || world == null)
            return false;

        int currentTick = tickCounter;

        // Re-sync target state from current tick data
        Object target = findMouseOverTarget(player, world);
        if (target == null) {
            // Missed swing — apply cancel rate
            return shouldCancel(missedSwingsCancelRate.getValue());
        }

        int targetId = McAccess.entityId(target);
        if (targetId < 0)
            return false;

        // Update current target to the hovered entity
        if (currentTargetId != targetId) {
            currentTarget = target;
            currentTargetId = targetId;
        }

        TargetState state = getOrCreateTargetState(targetId);
        int blockMask = getValidHitBlockMask(state, target, currentTick);

        boolean shouldBlock = (blockMask & BLOCK_WAIT_FIRST) != 0
                || (blockMask & BLOCK_PREDICTED_BURST) != 0
                || applyPauseDuration(state, blockMask & ~BLOCK_PREDICTED_BURST, currentTick);

        if (shouldBlock) {
            if (shouldCancel(inCombatCancelRate.getValue())) {
                if (fakeSwing.getValue())
                    doFakeSwing(player);
                return true;
            }
        } else {
            // Click passed — record the valid hit
            recordPassedValidHit(target, targetId, currentTick);
        }

        return false;
    }

    // ────────────────────────────────────────────────────────────────
    // Target / self state updates
    // ────────────────────────────────────────────────────────────────

    private void updateCurrentTarget(Object nextTarget, Object player, int currentTick) {
        if (sameTarget(nextTarget)) {
            if (nextTarget != null)
                getOrCreateTargetState(currentTargetId);
            return;
        }

        currentTarget = nextTarget;
        currentTargetId = nextTarget != null ? McAccess.entityId(nextTarget) : -1;

        if (nextTarget == null) {
            resetWaitFirstState();
        } else if (!waitFirstTracking) {
            waitFirstTracking = true;
            waitFirstStartTick = currentTick;
            waitFirstUnlocked = false;
            getOrCreateTargetState(currentTargetId);
        }
    }

    private void updateSelfDamage(Object player, int currentTick) {
        int hurtTime = McAccess.getInt(player, "field_70737_aN");
        boolean hurtAgain = hurtTime > lastSelfHurtTime;

        if (hurtAgain) {
            if (waitFirstTracking && !waitFirstUnlocked)
                waitFirstUnlocked = true;

            if (!takingKnockback)
                takingKnockback = true;

            if (currentTargetId >= 0) {
                TargetState state = getOrCreateTargetState(currentTargetId);
                state.firstSelfHitSeen = true;
            }
        }

        if (takingKnockback) {
            boolean onGround = McAccess.getBool(player, "field_70122_E");
            if (onGround && !hurtAgain)
                takingKnockback = false;
        }

        lastSelfHurtTime = hurtTime;
    }

    private void updateTargetDamage(int currentTick) {
        if (currentTargetId < 0 || !useServerAttackTime.getValue())
            return;

        Object target = getEntityById(currentTargetId);
        if (target == null)
            return;

        TargetState state = getOrCreateTargetState(currentTargetId);
        int targetHurtTime = McAccess.getInt(target, "field_70737_aN");

        // Timeout check
        if (state.pendingServerConfirmationTick >= 0
                && currentTick - state.pendingServerConfirmationTick > SERVER_TIMEOUT_TICKS) {
            state.pendingServerConfirmationTick = -1;
        }

        // Server confirmed the hit — target hurtTime increased
        if (state.pendingServerConfirmationTick >= 0
                && targetHurtTime > state.lastObservedTargetHurtTime) {
            state.pendingServerConfirmationTick = -1;
            state.lastConfirmedTargetDamageTick = currentTick;
            state.rawBlockMask = BLOCK_SERVER_COOLDOWN;
            state.rawBlockStartTick = currentTick;
        }

        state.lastObservedTargetHurtTime = targetHurtTime;
    }

    // ────────────────────────────────────────────────────────────────
    // Block mask computation
    // ────────────────────────────────────────────────────────────────

    private int getValidHitBlockMask(TargetState state, Object target, int currentTick) {
        if (currentTargetId < 0)
            return 0;

        if (disableDuringKnockback.getValue() && isTakingKnockback())
            return 0;

        int blockMask = 0;

        if (isWaitingForFirstHit(currentTick))
            blockMask |= BLOCK_WAIT_FIRST;

        blockMask |= getBurstBlockMask(state, target, currentTick);

        if (isCriticalsBlocked(state, currentTick))
            blockMask |= BLOCK_CRITICALS;

        return blockMask;
    }

    private int getBurstBlockMask(TargetState state, Object target, int currentTick) {
        if (useServerAttackTime.getValue()) {
            if (state.lastConfirmedTargetDamageTick >= 0
                    && currentTick - state.lastConfirmedTargetDamageTick < SERVER_COOLDOWN_TICKS) {
                return BLOCK_SERVER_COOLDOWN;
            }
            return 0;
        }

        if (!isPredictedBurstWindowActive(state, currentTick))
            return 0;

        int pauseTicks = msToTicks(pauseDuration.getValue());
        return (pauseTicks > 0 && currentTick - state.predictedBurstWindowStartTick < pauseTicks)
                ? BLOCK_PREDICTED_BURST
                : 0;
    }

    private boolean isCriticalsBlocked(TargetState state, int currentTick) {
        if (mode.getValue() != 1)
            return false;

        Object player = McAccess.thePlayer();
        if (player == null)
            return true;

        boolean onGround = McAccess.getBool(player, "field_70122_E");
        if (onGround)
            return false;

        if (onlyWhileDamaged.getValue() && !state.firstSelfHitSeen)
            return false;

        if (disableDuringKnockback.getValue() && isTakingKnockback())
            return false;

        return !canCriticalHit(player);
    }

    private boolean isWaitingForFirstHit(int currentTick) {
        if (waitForFirstHit.getValue() <= 0.0f
                || currentTargetId < 0
                || !waitFirstTracking
                || waitFirstUnlocked
                || waitFirstStartTick < 0) {
            return false;
        }

        int requiredTicks = msToTicks(waitForFirstHit.getValue());
        return requiredTicks > 0 && currentTick - waitFirstStartTick < requiredTicks;
    }

    private boolean canCriticalHit(Object player) {
        if (player == null)
            return false;

        Class<?> potionCls = McAccess.gameClass("net.minecraft.potion.Potion");
        boolean blinded = false;
        if (potionCls != null) {
            try {
                Field blindnessField = potionCls.getField("blindness");
                        Object blindness = blindnessField.get(null);
                if (blindness != null) {
                    Object r = McAccess.invoke(player, "func_70644_a",
                            new Class<?>[] { potionCls }, blindness);
                    blinded = r instanceof Boolean && (Boolean) r;
                }
            } catch (Throwable ignored) {
            }
        }

        return McAccess.getFloat(player, "field_70143_R") > 0.0f       // fallDistance > 0
                && !McAccess.getBool(player, "field_70122_E")            // !onGround
                && !isOnLadder(player)
                && !isInWater(player)
                && !blinded
                && McAccess.getObject(player, "field_70153_n") == null; // ridingEntity == null
    }

    // ────────────────────────────────────────────────────────────────
    // Pause duration helper
    // ────────────────────────────────────────────────────────────────

    private boolean applyPauseDuration(TargetState state, int blockMask, int currentTick) {
        if (blockMask == 0) {
            state.rawBlockMask = 0;
            state.rawBlockStartTick = -1;
            return false;
        }

        if (pauseDuration.getValue() <= 0.0f) {
            state.rawBlockMask = blockMask;
            state.rawBlockStartTick = currentTick;
            return false;
        }

        if (blockMask != state.rawBlockMask) {
            state.rawBlockMask = blockMask;
            state.rawBlockStartTick = currentTick;
        } else if (state.rawBlockStartTick < 0) {
            state.rawBlockStartTick = currentTick;
        }

        int requiredTicks = msToTicks(pauseDuration.getValue());
        return requiredTicks > 0 && currentTick - state.rawBlockStartTick < requiredTicks;
    }

    // ────────────────────────────────────────────────────────────────
    // Valid-hit recording
    // ────────────────────────────────────────────────────────────────

    private void recordPassedValidHit(Object target, int targetId, int currentTick) {
        if (target == null || targetId < 0)
            return;

        TargetState state = getOrCreateTargetState(targetId);

        if (useServerAttackTime.getValue()) {
            state.pendingServerConfirmationTick = currentTick;
            state.lastConfirmedTargetDamageTick = -1;
            return;
        }

        // Predicted burst: start the hurt window
        if (!isPredictedBurstWindowActive(state, currentTick))
            startPredictedBurstWindow(state, currentTick, HURT_WINDOW_TICKS);
    }

    // ────────────────────────────────────────────────────────────────
    // Predicted burst window
    // ────────────────────────────────────────────────────────────────

    private boolean isPredictedBurstWindowActive(TargetState state, int currentTick) {
        return state.predictedBurstWindowEndTick >= 0
                && currentTick < state.predictedBurstWindowEndTick;
    }

    private void startPredictedBurstWindow(TargetState state, int startTick, int windowTicks) {
        int hurtWindowTicks = Math.max(1, windowTicks);
        state.predictedBurstWindowStartTick = startTick;
        state.predictedBurstWindowEndTick = startTick + hurtWindowTicks;
    }

    // ────────────────────────────────────────────────────────────────
    // Chance gate
    // ────────────────────────────────────────────────────────────────

    private boolean shouldCancel(double chancePercent) {
        if (chancePercent <= 0.0)
            return false;
        if (chancePercent >= 100.0)
            return true;
        return random.nextDouble() * 100.0 < chancePercent;
    }

    // ────────────────────────────────────────────────────────────────
    // Target finding (nearest valid player in range)
    // ────────────────────────────────────────────────────────────────

    private Object findNearestTarget(Object player, Object world) {
        Class<?> playerCls = McAccess.gameClass("net.minecraft.entity.player.EntityPlayer");
        if (playerCls == null)
            return null;

        Object best = null;
        double bestDist = HIT_RANGE_SQ;

        for (Object entity : McAccess.getWorldEntities(world)) {
            if (entity == null || !playerCls.isInstance(entity))
                continue;
            if (entity == player)
                continue;
            if (!isValidTarget(entity))
                continue;

            double dx = McAccess.entityPosX(entity) - McAccess.entityPosX(player);
            double dy = McAccess.entityPosY(entity) - McAccess.entityPosY(player);
            double dz = McAccess.entityPosZ(entity) - McAccess.entityPosZ(player);
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq < bestDist) {
                bestDist = distSq;
                best = entity;
            }
        }

        return best;
    }

    private Object findMouseOverTarget(Object player, Object world) {
        Object mop = McAccess.objectMouseOver();
        if (mop == null)
            return null;
        Object hit = McAccess.getObject(mop, "field_72308_g");
        if (hit == null)
            return null;

        Class<?> playerCls = McAccess.gameClass("net.minecraft.entity.player.EntityPlayer");
        if (playerCls == null || !playerCls.isInstance(hit))
            return null;
        if (!isValidTarget((Object) hit))
            return null;

        return hit;
    }

    private static boolean isValidTarget(Object entity) {
        if (entity == null)
            return false;
        if (McAccess.getBool(entity, "field_70128_L"))
            return false;
        if (entity == McAccess.thePlayer())
            return false;
        return true;
    }

    // ────────────────────────────────────────────────────────────────
    // Utility helpers
    // ────────────────────────────────────────────────────────────────

    private static int msToTicks(float ms) {
        if (ms <= 0.0f)
            return 0;
        return (int) Math.ceil(ms / 50.0);
    }

    private boolean sameTarget(Object nextTarget) {
        if (currentTarget == null || nextTarget == null)
            return currentTarget == nextTarget;
        return McAccess.entityId(currentTarget) == McAccess.entityId(nextTarget);
    }

    private boolean isTakingKnockback() {
        Object player = McAccess.thePlayer();
        if (player == null)
            return false;
        return takingKnockback || McAccess.getInt(player, "field_70737_aN") > 0;
    }

    private void doFakeSwing(Object player) {
        if (player == null)
            return;
        McAccess.invoke(player, "func_71038_i", new Class<?>[0]); // swingItem
    }

    private static boolean isOnLadder(Object player) {
        Object r = McAccess.invoke(player, "func_70617_f_", new Class<?>[0]);
        return r instanceof Boolean && (Boolean) r;
    }

    private static boolean isInWater(Object player) {
        Object r = McAccess.invoke(player, "func_70090_H", new Class<?>[0]);
        return r instanceof Boolean && (Boolean) r;
    }

    private Object getEntityById(int entityId) {
        Object world = McAccess.theWorld();
        if (world == null)
            return null;
        for (Object entity : McAccess.getWorldEntities(world)) {
            if (entity != null && McAccess.entityId(entity) == entityId)
                return entity;
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────────
    // Target state management
    // ────────────────────────────────────────────────────────────────

    private TargetState getOrCreateTargetState(int entityId) {
        TargetState state = targetStates.get(entityId);
        if (state == null) {
            state = new TargetState();
            if (useServerAttackTime.getValue()) {
                Object entity = getEntityById(entityId);
                if (entity != null)
                    state.lastObservedTargetHurtTime =
                            McAccess.getInt(entity, "field_70737_aN");
            }
            targetStates.put(entityId, state);
        }
        return state;
    }

    private void pruneTargetStates(Object world) {
        if (world == null) {
            targetStates.clear();
            return;
        }

        Iterator<Map.Entry<Integer, TargetState>> it =
                targetStates.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, TargetState> entry = it.next();
            int id = entry.getKey();
            Object entity = getEntityById(id);
            if (entity == null
                    || McAccess.getBool(entity, "field_70128_L")) {
                it.remove();
            }
        }
    }

    private void resetAllState() {
        currentTarget = null;
        currentTargetId = -1;
        targetStates.clear();
        lastSelfHurtTime = 0;
        takingKnockback = false;
        resetWaitFirstState();
    }

    private void resetWaitFirstState() {
        waitFirstTracking = false;
        waitFirstStartTick = -1;
        waitFirstUnlocked = false;
    }

    // ────────────────────────────────────────────────────────────────
    // TargetState inner class
    // ────────────────────────────────────────────────────────────────

    private static final class TargetState {
        boolean firstSelfHitSeen;
        int lastConfirmedTargetDamageTick = -1;
        int pendingServerConfirmationTick = -1;
        int predictedBurstWindowStartTick = -1;
        int predictedBurstWindowEndTick = -1;
        int lastObservedTargetHurtTime;
        int rawBlockStartTick = -1;
        int rawBlockMask;
    }
}
