package gnu.client.module.modules.combat;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.ModeSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.NativeBootstrap;
import gnu.client.runtime.mc.McAccess;
import gnu.client.runtime.packet.PacketEvents;
import gnu.client.runtime.packet.PacketHelper;
import gnu.client.runtime.packet.PacketListener;

import java.util.Arrays;
import java.util.List;

/**
 * WTap / SuperKnockback — three modes ported from LiquidBounce + Raven.
 *
 * <p><b>Packet</b> (SuperKnockback): On attack, sends C0B EntityAction sprint
 * stop/start burst directly to the server via {@link McAccess#sendSprintActionPacket}.
 * Each {stop,start} pair forces the server to recalculate knockback, producing
 * maximum KB regardless of client-side sprint state. Default 3 bursts =
 * stop→start→stop→start→stop→start (6 C0B packets).</p>
 *
 * <p><b>SprintTap</b> (Raven-style): Sends one C0B STOP_SPRINTING immediately
 * on attack, then blocks client sprint key for controlled ticks via
 * {@link #shouldSuppressSprintKey()}. Vanilla sprint-stop logic sends the
 * C0B naturally on the movement tick.</p>
 *
 * <p><b>Legit</b> (OpenMyau / old WTap): Temporarily zeros {@code moveForward}
 * on the MovementInput so vanilla stops sprinting without any packet injection.
 * Most AC-friendly but least reliable KB increase.</p>
 */
public final class WTapModule extends Module implements PacketListener {

    private static final float FORWARD_STOP_THRESHOLD = 0.8f;
    private static final long MS_PER_TICK = 50L;

    private static final List<String> MODE_NAMES = Arrays.asList("Packet", "SprintTap", "Legit");

    // ── Settings ──────────────────────────────────────────────────────────

    private final ModeSetting mode = addSetting(
            new ModeSetting("Mode", 0, MODE_NAMES));
    private final SliderSetting chance = addSetting(
            new SliderSetting("Chance", 100.0f, 0.0f, 100.0f));
    private final SliderSetting hurtTime = addSetting(
            new SliderSetting("HurtTime", 10.0f, 0.0f, 10.0f));
    private final SliderSetting attackWindow = addSetting(
            new SliderSetting("AttackWindow", 500.0f, 50.0f, 2000.0f));
    private final BoolSetting playersOnly = addSetting(
            new BoolSetting("Players only", true));
    private final BoolSetting condMousePressed = addSetting(
            new BoolSetting("CondMousePressed", false));
    private final BoolSetting condDamage = addSetting(
            new BoolSetting("CondDamage", false));
    private final BoolSetting condFalling = addSetting(
            new BoolSetting("CondFalling", false));
    private final BoolSetting disableInWater = addSetting(
            new BoolSetting("Disable in water", true));
    private final BoolSetting backwardOnly = addSetting(
            new BoolSetting("Backward only", false));

    // Packet-mode burst count (default 3 = stop→start→stop→start→stop→start)
    private final SliderSetting packetBursts = addSetting(
            new SliderSetting("Packet bursts", 3.0f, 1.0f, 6.0f));
    private final BoolSetting packetRestoreSprint = addSetting(
            new BoolSetting("Restore sprint", true));

    // SprintTap delay/wait
    private final SliderSetting sprintTapDelay = addSetting(
            new SliderSetting("SprintTap delay", 1.0f, 0.0f, 6.0f));
    private final SliderSetting sprintTapWait = addSetting(
            new SliderSetting("SprintTap wait", 2.0f, 0.0f, 10.0f));

    // Legit mode settings (existing OpenMyau approach)
    private final SliderSetting legitTickDelay = addSetting(
            new SliderSetting("Legit tick delay", 5.5f, 0.0f, 10.0f));
    private final SliderSetting legitDuration = addSetting(
            new SliderSetting("Legit duration", 1.5f, 0.5f, 5.0f));
    private final SliderSetting legitStartDelay = addSetting(
            new SliderSetting("Legit start delay", 0.0f, 0.0f, 10.0f));
    private final BoolSetting legitSprintingOnly = addSetting(
            new BoolSetting("Legit sprinting only", true));
    private final BoolSetting legitDisableOnHit = addSetting(
            new BoolSetting("Legit disable on hit", false));

    // ── State ─────────────────────────────────────────────────────────────

    private boolean active;
    private boolean stopForward;
    private long delayTicksRemaining;
    private long durationTicksRemaining;
    private long lastWTapMs;
    private int lastAttackTick = -1;

    // SprintTap blocking state
    private boolean sprintTapBlocking;
    private int sprintTapBlockTicks;

    // World/player change guard
    private Object lastPlayer;
    private Object lastWorld;

    public WTapModule() {
        super("W Tap", "Resets sprint to increase knockback (SuperKnockback)", Category.COMBAT);
    }

    // ── Static hooks for external modules ─────────────────────────────────

    /**
     * SprintTap mode: SprintModule checks this to release the sprint keybind
     * while the post-attack block window is active.
     */
    public static boolean shouldSuppressSprintKey() {
        Module mod = ModuleManager.INSTANCE.getModule("W Tap");
        if (!(mod instanceof WTapModule) || !mod.isEnabled())
            return false;
        WTapModule wtap = (WTapModule) mod;
        return "SprintTap".equals(wtap.mode.getCurrentMode()) && wtap.sprintTapBlocking;
    }

    /**
     * Legit mode: called from BridgeAssistModule's JVMTI-patched
     * {@code updatePlayerMoveState} after vanilla key read. Brief
     * {@code moveForward = 0} lets vanilla sprint-stop logic run.
     */
    public static void patchMovementInput(Object movInput) {
        Module mod = ModuleManager.INSTANCE.getModule("W Tap");
        if (!(mod instanceof WTapModule))
            return;
        WTapModule wtap = (WTapModule) mod;
        String m = wtap.mode.getCurrentMode();
        if (!"Legit".equals(m))
            return;
        if (!wtap.isEnabled() || !wtap.stopForward || movInput == null)
            return;
        McAccess.setFloat(movInput, "field_78902_a", 0.0f);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        clearState();
        PacketEvents.register(this);
    }

    @Override
    public void onDisable() {
        PacketEvents.unregister(this);
        clearState();
    }

    private void clearState() {
        active = false;
        stopForward = false;
        delayTicksRemaining = 0L;
        durationTicksRemaining = 0L;
        lastWTapMs = 0L;
        lastAttackTick = -1;
        sprintTapBlocking = false;
        sprintTapBlockTicks = 0;
        lastPlayer = null;
        lastWorld = null;
    }

    // ── Tick handlers ─────────────────────────────────────────────────────

    @Override
    public void onTickStart() {
        if (!isEnabled())
            return;
        checkWorldOrPlayerChange();

        // SprintTap mode: decrement block ticks every tick
        if (sprintTapBlocking) {
            if (sprintTapBlockTicks <= 0) {
                sprintTapBlocking = false;
            } else {
                sprintTapBlockTicks--;
            }
        }
    }

    @Override
    public void onTick() {
        if (!isEnabled())
            return;

        Object player = McAccess.thePlayer();
        if (player == null) {
            clearState();
            return;
        }

        checkWorldOrPlayerChange();

        String m = mode.getCurrentMode();
        if ("Legit".equals(m)) {
            tickLegit(player);
        }
    }

    private void tickLegit(Object player) {
        if (!active)
            return;

        if (!stopForward && !canTrigger(player)) {
            clearState();
            return;
        }

        if (delayTicksRemaining > 0L) {
            delayTicksRemaining -= MS_PER_TICK;
            return;
        }

        stopForward = true;
        durationTicksRemaining -= MS_PER_TICK;
        if (durationTicksRemaining <= 0L)
            clearState();
    }

    private void checkWorldOrPlayerChange() {
        Object player = McAccess.thePlayer();
        Object world = McAccess.theWorld();
        if (player == null || world == null) {
            clearState();
            return;
        }
        if (lastPlayer != null && lastPlayer != player)
            clearState();
        else if (lastWorld != null && lastWorld != world)
            clearState();
        lastPlayer = player;
        lastWorld = world;
    }

    // ── Packet events ─────────────────────────────────────────────────────

    @Override
    public boolean onSend(Object packet) {
        // Fix 2: Suppress C0A arm swing during AutoBlock lag window.
        // The server has isUsingItem=true and cannot process swings anyway;
        // sending C0A while lagging causes BadPacketsX flags.
        if (packet != null && PacketHelper.isAnimationPacket(packet)) {
            Module autoBlock = ModuleManager.INSTANCE.getModule("Auto Block");
            if (autoBlock instanceof AutoBlockModule && autoBlock.isEnabled()
                    && ((AutoBlockModule) autoBlock).isActive()) {
                return true; // cancel the C0A swing (isActive = isBlocking || isLagging)
            }
        }
        return false;
    }

    @Override
    public boolean onReceive(Object packet) {
        String m = mode.getCurrentMode();
        if ("Legit".equals(m) && legitDisableOnHit.getValue()) {
            if (PacketHelper.isSelfEntityVelocity(packet)) {
                clearState();
            }
        }
        return false;
    }

    // ── Attack entry point ────────────────────────────────────────────────

    /**
     * Called from VanillaModuleDriver and ClientEventListener on Forge
     * AttackEntityEvent.
     */
    public void noteForgeAttack(Object target) {
        tryBeginSequence(target);
    }

    private void tryBeginSequence(Object target) {
        if (!isEnabled() || target == null || active)
            return;
        if (!isLivingAttackTarget(target))
            return;

        Object player = McAccess.thePlayer();
        if (player == null || target == player)
            return;

        // ── Condition gates ────────────────────────────────────────────────

        // Players-only filter
        if (playersOnly.getValue()) {
            Class<?> playerCls = McAccess.gameClass("net.minecraft.entity.player.EntityPlayer");
            if (playerCls == null || !playerCls.isInstance(target))
                return;
        }

        if (condMousePressed.getValue() && !NativeBootstrap.isLeftMouseDown())
            return;

        if (condDamage.getValue() && McAccess.getInt(player, "field_70737_aN") <= 0)
            return;

        if (condFalling.getValue() && McAccess.getFloat(player, "field_70143_R") <= 0.0f)
            return;

        if (disableInWater.getValue() && isInWater(player))
            return;

        // HurtTime check on target (LiquidBounce: only fire if target is
        // vulnerable — hurtTime <= hurtTime setting)
        int targetHurtTime = McAccess.getInt(target, "field_70725_aQ");
        if (targetHurtTime > hurtTime.getValue().intValue())
            return;

        // Chance gate (LiquidBounce: random % roll)
        if (chance.getValue() < 100.0f && Math.random() * 100.0 >= chance.getValue())
            return;

        // Attack window cooldown
        long now = System.currentTimeMillis();
        if (lastWTapMs > 0L && now - lastWTapMs < attackWindow.getValue().longValue())
            return;

        // Backward-only gate
        if (backwardOnly.getValue()) {
            Object movInput = movementInput(player);
            if (movInput != null && McAccess.getFloat(movInput, "field_78902_a") >= 0f)
                return;
        }

        // Same-tick dedup
        int tick = McAccess.getInt(player, "field_70173_aa");
        if (tick == lastAttackTick)
            return;
        lastAttackTick = tick;

        lastWTapMs = now;

        String m = mode.getCurrentMode();

        switch (m) {
            case "Packet":
                doPacketBurst(player);
                break;
            case "SprintTap":
                doSprintTap(player);
                break;
            case "Legit":
                doLegitSequence();
                break;
        }
    }

    // ── Mode implementations ──────────────────────────────────────────────

    /**
     * Packet mode (SuperKnockback — LiquidBounce).
     *
     * Sends {stop→start}×bursts C0B EntityAction packets directly to the
     * server via {@link McAccess#sendSprintActionPacket}. Each pair forces
     * the server to recalculate knockback for the hit.
     *
     * LiquidBounce uses 3 bursts: stop→start→stop→start→stop→start.
     * After the burst, restores client sprint flags so the local player
     * visually stays sprinting.
     */
    private void doPacketBurst(Object player) {
        if (player == null)
            return;

        // Save sprint flags for restore
        boolean wasClientSprinting = McAccess.isClientSprinting(player);
        boolean wasServerSprinting = McAccess.getServerSprintState(player);

        // Save wasSprinting field too (field_20052_b in 1.8.9)
        boolean wasWasSprinting = McAccess.getBool(player, "field_20052_b");

        int bursts = packetBursts.getValue().intValue();
        if (bursts < 1)
            bursts = 1;
        if (bursts > 6)
            bursts = 6;

        // Send the sprint toggle sequence: stop→start × bursts
        // Each pair triggers knockback recalculation
        for (int i = 0; i < bursts; i++) {
            McAccess.sendSprintActionPacket(player, false); // STOP_SPRINTING
            McAccess.sendSprintActionPacket(player, true);  // START_SPRINTING
        }

        // Restore client-side sprint state so the local player doesn't
        // appear to stop sprinting
        if (packetRestoreSprint.getValue()) {
            McAccess.setClientSprinting(player, wasClientSprinting);
            McAccess.setBool(player, "field_20052_b", wasWasSprinting);
            // Server state needs to be set back too since sendSprintActionPacket
            // mirrors to serverSprintState
            McAccess.setServerSprintState(player, wasServerSprinting);
        }

        active = true;
    }

    /**
     * SprintTap mode (Raven style).
     *
     * Sends one C0B STOP_SPRINTING immediately on attack, then blocks
     * the client sprint key for (delay + wait) ticks. During the block
     * window, SprintModule releases the sprint keybind, so the next
     * movement tick sends the natural C0B stop.
     */
    private void doSprintTap(Object player) {
        if (player == null)
            return;

        // Send immediate C0B STOP_SPRINTING
        McAccess.sendSprintActionPacket(player, false);

        // Block sprint for the combined delay+wait duration
        active = true;
        sprintTapBlocking = true;
        sprintTapBlockTicks = sprintTapDelay.getValue().intValue() + sprintTapWait.getValue().intValue();
    }

    /**
     * Legit mode (OpenMyau / existing behaviour).
     *
     * Schedule delayed {@code moveForward = 0} on the client's MovementInput,
     * letting vanilla sprint-stop logic send the C0B naturally on the next
     * movement tick. No direct packet injection.
     */
    private void doLegitSequence() {
        active = true;
        stopForward = false;
        delayTicksRemaining = (long) ((legitTickDelay.getValue() + legitStartDelay.getValue()) * MS_PER_TICK);
        durationTicksRemaining = (long) (legitDuration.getValue() * MS_PER_TICK);
    }

    // ── Legit-mode helpers ────────────────────────────────────────────────

    private boolean canTrigger(Object player) {
        Object movInput = movementInput(player);
        if (movInput != null && McAccess.getFloat(movInput, "field_78902_a") < FORWARD_STOP_THRESHOLD)
            return false;

        if (McAccess.getBool(player, "field_70134_J"))
            return false;

        if (!hasSprintFood(player))
            return false;

        if (legitSprintingOnly.getValue()) {
            if (!McAccess.isClientSprinting(player))
                return false;
        }

        return McAccess.isClientSprinting(player) || McAccess.isKeyBindDown("field_151444_V");
    }

    private static boolean hasSprintFood(Object player) {
        Object food = McAccess.invoke(player, "func_71024_bL", new Class<?>[0]);
        if (food == null)
            return true;
        Object level = McAccess.invoke(food, "func_75116_a", new Class<?>[0]);
        if (level instanceof Integer) {
            Object caps = McAccess.getObject(player, "field_71075_bZ");
            if (caps != null && McAccess.getBool(caps, "field_75101_c"))
                return true;
            return (Integer) level > 6;
        }
        return true;
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private static Object movementInput(Object player) {
        Object movInput = McAccess.getObject(player, "field_71158_b");
        if (movInput == null)
            movInput = McAccess.getObject(player, "field_71158_b");
        return movInput;
    }

    private static boolean isLivingAttackTarget(Object entity) {
        if (entity == null)
            return false;
        Class<?> c = entity.getClass();
        while (c != null) {
            String name = c.getName();
            if (name.contains("EntityLivingBase") || name.contains("EntityPlayer"))
                return true;
            c = c.getSuperclass();
        }
        return false;
    }

    private static boolean isInWater(Object player) {
        Object r = McAccess.invoke(player, "func_70090_H", new Class<?>[0]);
        return r instanceof Boolean && (Boolean) r;
    }
}
