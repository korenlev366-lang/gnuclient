package gnu.client.module.modules.combat;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.ModeSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.module.modules.network.LagrangeModule;
import gnu.client.runtime.MoveFixUtil;
import gnu.client.runtime.AuraCombatPacketGuard;
import gnu.client.runtime.VanillaModuleDriver;
import gnu.client.runtime.PlayerUpdateHook;
import gnu.client.runtime.RotationState;
import gnu.client.runtime.mc.McAccess;

import java.util.Arrays;
import java.util.List;

/**
 * Attacks the nearest player in range with optional silent rotations and silent movefix.
 *
 * <p>OpenMyau-style silent: smooth aim yaw/pitch on C03 while a target is in range.
 * Rotation is prepared in {@code onPreUpdate} (before {@code onLivingUpdate}) so
 * {@code fixStrafe} and the {@code moveFlying} yaw swap ({@link gnu.client.runtime.EntityLivingBaseHook})
 * run on the same tick as the packet — matching OpenMyau {@code MixinEntityLivingBase}.
 */
public final class KillAuraModule extends Module {

    private static final int ROT_NONE = 0;
    private static final int ROT_SILENT = 1;

    private static final int MOVEFIX_NONE = 0;
    private static final int MOVEFIX_SILENT = 1;

    private static final int ROTATION_PRIORITY = MoveFixUtil.KILLAURA_MOVE_FIX_PRIORITY;
    private static final double MULTIPOINT_OFFSET = 50.0;

    private final SliderSetting range = addSetting(new SliderSetting("Range", 4.0f, 2.0f, 6.0f));
    private final SliderSetting cps = addSetting(new SliderSetting("CPS", 10.0f, 1.0f, 20.0f));
    private final ModeSetting rotations = addSetting(new ModeSetting("Rotations", ROT_SILENT,
        Arrays.asList("NONE", "SILENT")));
    private final ModeSetting moveFix = addSetting(new ModeSetting("Move-fix", MOVEFIX_SILENT,
        Arrays.asList("NONE", "SILENT")));
    private final SliderSetting horizontalSpeed = addSetting(new SliderSetting("Horizontal speed", 40.0f, 1.0f, 100.0f));
    private final SliderSetting verticalSpeed = addSetting(new SliderSetting("Vertical speed", 40.0f, 0.0f, 100.0f));
    private final SliderSetting maximumFov = addSetting(new SliderSetting("MaximumFov", 180.0f, 15.0f, 360.0f));
    private final BoolSetting multipoint = addSetting(new BoolSetting("Multipoint", false));

    private long lastAttackMs = 0L;
    private Object attackTarget;
    private float lastSentYaw = Float.MIN_VALUE;
    private float lastSentPitch = Float.MIN_VALUE;
    private float pendingSentYaw = Float.MIN_VALUE;
    private float pendingSentPitch = Float.MIN_VALUE;
    /** Blocks sprint restart same tick after aura attack (Grim BadPacketsX). */
    private boolean attackedThisTick;

    public KillAuraModule() {
        super("KillAura", "Attacks nearest player in range", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        resetRotationState();
        attackTarget = null;
        AuraCombatPacketGuard.register();
    }

    @Override
    public void onDisable() {
        clearRotationStateIfOwned();
        attackTarget = null;
        resetRotationState();
        attackedThisTick = false;
        AuraCombatPacketGuard.unregister();
    }

    @Override
    public void onTick() {
        if (rotations.getValue() != ROT_NONE)
            return;
        Object player = McAccess.thePlayer();
        if (player == null || !canRunCombat())
            return;
        attackTarget = findTarget(player);
        tryPerformAttack(player);
    }

    public static boolean shouldYieldAimAssist() {
        Module module = ModuleManager.instance().getModule("KillAura");
        if (!(module instanceof KillAuraModule) || !module.isEnabled())
            return false;
        KillAuraModule killAura = (KillAuraModule) module;
        return killAura.rotations.getValue() == ROT_SILENT && killAura.attackTarget != null;
    }

    public static Object getCurrentTarget() {
        Module module = ModuleManager.instance().getModule("KillAura");
        if (!(module instanceof KillAuraModule) || !module.isEnabled())
            return null;
        return ((KillAuraModule) module).attackTarget;
    }

    public static void onPreUpdate(Object player) {
        Module module = ModuleManager.instance().getModule("KillAura");
        if (module instanceof KillAuraModule && module.isEnabled())
            ((KillAuraModule) module).preUpdate(player);
    }

    public static void onBeforeWalkingPrepare(Object player) {
        Module module = ModuleManager.instance().getModule("KillAura");
        if (module instanceof KillAuraModule && module.isEnabled())
            ((KillAuraModule) module).beforeWalkingPrepare(player);
    }

    public static void onBeforeWalkingAttack(Object player) {
        Module module = ModuleManager.instance().getModule("KillAura");
        if (module instanceof KillAuraModule && module.isEnabled())
            ((KillAuraModule) module).beforeWalkingAttack(player);
    }

    /** After aura attack in preUpdate — block START_SPRINTING until C03 is sent (BadPacketsX). */
    public static boolean shouldSuppressSprintRestart() {
        Module module = ModuleManager.instance().getModule("KillAura");
        if (!(module instanceof KillAuraModule) || !module.isEnabled())
            return false;
        return ((KillAuraModule) module).attackedThisTick;
    }

    public static void onPostAttackTick(Object player) {
        Module module = ModuleManager.instance().getModule("KillAura");
        if (module instanceof KillAuraModule && module.isEnabled())
            ((KillAuraModule) module).clearPostAttackGuard();
    }

    public static void patchMovementInput(Object movInput) {
        if (movInput == null)
            return;
        Module module = ModuleManager.instance().getModule("KillAura");
        KillAuraModule killAura = module instanceof KillAuraModule && module.isEnabled()
            ? (KillAuraModule) module : null;
        if (killAura == null)
            return;

        if (killAura.rotations.getValue() != ROT_SILENT
            || killAura.moveFix.getValue() != MOVEFIX_SILENT
            || !MoveFixUtil.hasMoveFixPriority(ROTATION_PRIORITY)
            || !MoveFixUtil.isForwardPressed())
            return;

        boolean sneak = McAccess.getBool(movInput, "field_78899_d");
        float[] fixed = MoveFixUtil.fixStrafe(
            McAccess.getYaw(), RotationState.getSmoothedYaw(), sneak);
        McAccess.setFloat(movInput, "field_78900_b", fixed[0]);
        McAccess.setFloat(movInput, "field_78902_a", fixed[1]);
    }

    private void preUpdate(Object player) {
        pendingSentYaw = Float.MIN_VALUE;
        pendingSentPitch = Float.MIN_VALUE;
        attackTarget = null;

        if (player == null || !canRunCombat()) {
            clearRotationStateIfOwned();
            return;
        }

        Object target = findTarget(player);
        if (target == null) {
            clearRotationStateIfOwned();
            return;
        }

        attackTarget = target;
        if (rotations.getValue() == ROT_SILENT) {
            prepareSilentRotation(player);
            tryPerformAttack(player);
        }
    }

    private void beforeWalkingPrepare(Object player) {
        if (player == null || pendingSentYaw == Float.MIN_VALUE || rotations.getValue() != ROT_SILENT)
            return;
        if (PlayerUpdateHook.hasRotationOverride())
            return;
        PlayerUpdateHook.requestRotation(pendingSentYaw, pendingSentPitch);
    }

    private void beforeWalkingAttack(Object player) {
        // Silent attacks run in preUpdate (before onLivingUpdate) so vanilla sprint
        // attack slow applies before movement — packet still sends later in C03.
    }

    private void prepareSilentRotation(Object player) {
        if (player == null || attackTarget == null)
            return;

        if (MoveFixUtil.isForwardPressed() && moveFix.getValue() == MOVEFIX_NONE) {
            clearRotationStateIfOwned();
            return;
        }

        float baseYaw = lastSentYaw != Float.MIN_VALUE
            ? lastSentYaw
            : PlayerUpdateHook.lastReportedYaw(player);
        float basePitch = lastSentPitch != Float.MIN_VALUE
            ? lastSentPitch
            : PlayerUpdateHook.lastReportedPitch(player);

        double mp = multipoint.getValue() ? MULTIPOINT_OFFSET : 0.0;
        float[] rawTarget = RavenRotationUtils.getRawRotationsToTarget(
            attackTarget, mp, mp, false, range.getValue(), true, false);
        if (rawTarget == null)
            return;

        int hSpeed = Math.round(horizontalSpeed.getValue());
        int vSpeed = Math.round(verticalSpeed.getValue());
        float[] result = RavenRotationUtils.smoothRotationHv(
            baseYaw, basePitch, rawTarget[0], rawTarget[1], hSpeed, vSpeed, 0.0f);

        pendingSentYaw = result[0];
        pendingSentPitch = result[1];
        sendSilentRotation(pendingSentYaw, pendingSentPitch);
    }

    private boolean shouldSkipAttackForAutoBlockLag() {
        Module autoBlock = ModuleManager.INSTANCE.getModule("Auto Block");
        if (!(autoBlock instanceof AutoBlockModule) || !autoBlock.isEnabled())
            return false;
        AutoBlockModule block = (AutoBlockModule) autoBlock;
        return block.isLagging() && block.isHoldThroughLag();
    }

    private boolean canAttackThisTick(Object player) {
        if (player == null || attackTarget == null || !canRunCombat())
            return false;
        if (shouldSkipAttackForAutoBlockLag())
            return false;
        long now = System.currentTimeMillis();
        long intervalMs = (long) (1000.0f / cps.getValue());
        if (now - lastAttackMs < intervalMs)
            return false;
        if (HitSelectModule.shouldBlockClick())
            return false;
        return McAccess.playerController() != null;
    }

    private void tryPerformAttack(Object player) {
        if (!canAttackThisTick(player))
            return;

        notifyPreAttackHooks(attackTarget);

        boolean wasClientSprinting = McAccess.isClientSprinting(player);
        boolean wasServerSprinting = McAccess.getServerSprintState(player);

        float savedYaw = 0f;
        float savedPitch = 0f;
        boolean useSilentAim = rotations.getValue() == ROT_SILENT && pendingSentYaw != Float.MIN_VALUE;
        if (useSilentAim) {
            savedYaw = McAccess.getFloat(player, "field_70177_z");
            savedPitch = McAccess.getFloat(player, "field_70125_A");
            McAccess.setFloat(player, "field_70177_z", pendingSentYaw);
            McAccess.setFloat(player, "field_70125_A", pendingSentPitch);
        }

        boolean swing = !shouldDeferSwingToAutoBlock();
        boolean attacked = McAccess.attackEntity(attackTarget, swing);

        if (useSilentAim) {
            McAccess.setFloat(player, "field_70177_z", savedYaw);
            McAccess.setFloat(player, "field_70125_A", savedPitch);
        }

        if (!attacked)
            return;

        attackedThisTick = true;
        McAccess.reconcileVanillaAttackSlowdown(player, wasClientSprinting, wasServerSprinting);

        lastAttackMs = System.currentTimeMillis();
        AimAssistModule.lastClickMs = lastAttackMs;
    }

    private void clearPostAttackGuard() {
        attackedThisTick = false;
    }

    private static void notifyPreAttackHooks(Object target) {
        VanillaModuleDriver.noteAttack(target);
        Module lagrange = ModuleManager.INSTANCE.getModule("Lagrange");
        if (lagrange instanceof LagrangeModule && lagrange.isEnabled())
            ((LagrangeModule) lagrange).noteForgeAttack(target);
    }

    private static boolean shouldDeferSwingToAutoBlock() {
        Module autoBlock = ModuleManager.INSTANCE.getModule("Auto Block");
        if (autoBlock instanceof AutoBlockModule && autoBlock.isEnabled())
            return ((AutoBlockModule) autoBlock).killAuraShouldDeferSwing();
        return false;
    }

    private void sendSilentRotation(float sentYaw, float sentPitch) {
        PlayerUpdateHook.requestRotation(sentYaw, sentPitch);
        lastSentYaw = sentYaw;
        lastSentPitch = sentPitch;
        // Movefix (fixStrafe + moveFlying yaw swap) only while actually moving.
        boolean moveFixEnabled = moveFix.getValue() == MOVEFIX_SILENT
            && MoveFixUtil.isForwardPressed();
        RotationState.applyState(
            true,
            sentYaw,
            sentPitch,
            sentYaw,
            moveFixEnabled ? ROTATION_PRIORITY : -1);
    }

    private boolean canRunCombat() {
        Object player = McAccess.thePlayer();
        Object world = McAccess.theWorld();
        if (player == null || world == null)
            return false;

        Module autoClicker = ModuleManager.INSTANCE.getModule("AutoClicker");
        if (autoClicker != null && autoClicker.isEnabled())
            return false;

        return true;
    }

    private Object findTarget(Object player) {
        Object world = McAccess.theWorld();
        if (world == null)
            return null;

        Class<?> playerCls = McAccess.gameClass("net.minecraft.entity.player.EntityPlayer");
        if (playerCls == null)
            return null;

        List<?> players = McAccess.getWorldEntitiesFiltered(world);
        if (players.isEmpty())
            return null;

        float cameraYaw = McAccess.getYaw();
        float fov = maximumFov.getValue();
        double maxRange = range.getValue();
        double bestDistSq = maxRange * maxRange;
        Object bestEntity = null;

        for (Object entity : players) {
            if (entity == null || entity == player || !playerCls.isInstance(entity))
                continue;

            // Raven/OpenMyau: eye-to-hitbox reach, not center distance — keeps target
            // while overlapping (center dist can be <0.1 and used to drop target → movefix flicker).
            double distSq = RavenRotationUtils.distanceSqFromEyeToClosestOnAabb(entity);
            if (distSq >= bestDistSq)
                continue;

            if (fov < 360.0f
                && !RavenRotationUtils.isEyeInsideExpandedHitbox(entity)
                && !RavenRotationUtils.inFov(cameraYaw, fov, RavenRotationUtils.angleToEntity(entity)))
                continue;

            bestDistSq = distSq;
            bestEntity = entity;
        }

        return bestEntity;
    }

    private void clearRotationStateIfOwned() {
        if (RotationState.getPriority() == ROTATION_PRIORITY)
            RotationState.reset();
    }

    private void resetRotationState() {
        lastSentYaw = Float.MIN_VALUE;
        lastSentPitch = Float.MIN_VALUE;
        pendingSentYaw = Float.MIN_VALUE;
        pendingSentPitch = Float.MIN_VALUE;
    }
}
