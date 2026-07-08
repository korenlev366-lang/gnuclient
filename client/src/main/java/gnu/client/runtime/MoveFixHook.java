package gnu.client.runtime;

import gnu.client.runtime.mc.McAccess;

/**
 * OpenMyau {@code MixinEntityLivingBase} parity without relying on patching
 * {@code EntityLivingBase} (class-load order / obfuscation issues).
 *
 * <p>{@link EntityLivingBaseHook} swaps yaw only inside {@code moveFlying} (OpenMyau-accurate).
 * This hook optionally wraps all of {@code onLivingUpdate} for scaffold when the transformer
 * patch is unavailable.
 */
public final class MoveFixHook {

    private static final String FIELD_ROTATION_YAW = "field_70177_z";

    private static float savedYaw;
    private static int swapDepth;

    private MoveFixHook() {}

    public static void beforeLivingUpdate(Object player) {
        if (player == null || !isLocalPlayer(player) || !shouldUseServerMoveYaw())
            return;
        if (swapDepth == 0)
            savedYaw = McAccess.getFloat(player, FIELD_ROTATION_YAW);
        McAccess.setFloat(player, FIELD_ROTATION_YAW, PlayerUpdateHook.silentYawForMoveFix());
        swapDepth++;
    }

    public static void afterLivingUpdate(Object player) {
        if (player == null || swapDepth <= 0)
            return;
        swapDepth--;
        if (swapDepth == 0)
            McAccess.setFloat(player, FIELD_ROTATION_YAW, savedYaw);
    }

    /** Used by {@link EntityLivingBaseHook} when that patch is also present. */
    public static boolean shouldUseServerMoveYaw() {
        if (!RotationState.isActived())
            return false;
        int priority = (int) RotationState.getPriority();
        // OpenMyau: swap yaw inside moveFlying whenever silent movefix is active.
        // Must NOT gate on isRotationTick — rotation is prepared in preUpdate before
        // onLivingUpdate, but beginRotationSwap (isRotationTick) happens later at C03.
        return priority == MoveFixUtil.KILLAURA_MOVE_FIX_PRIORITY
            || priority == MoveFixUtil.SCAFFOLD_MOVE_FIX_PRIORITY;
    }

    private static boolean isLocalPlayer(Object player) {
        Object local = McAccess.thePlayer();
        return local != null && player == local;
    }
}
