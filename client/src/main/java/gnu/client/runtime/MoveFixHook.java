package gnu.client.runtime;

import gnu.client.runtime.mc.McAccess;

/**
 * OpenMyau {@code MixinEntityLivingBase} parity without relying on patching
 * {@code EntityLivingBase} (class-load order / obfuscation issues).
 *
 * <p>Wraps {@code EntityPlayerSP.onLivingUpdate}: while scaffold movefix is active,
 * client physics uses the silent/server yaw so {@code fixStrafe} input (derived from
 * client look + keys) produces world movement in the direction the player is looking.
 */
public final class MoveFixHook {

    private static final String FIELD_ROTATION_YAW = "field_70177_z";
    private static final float SCAFFOLD_MOVE_FIX_PRIORITY = 3.0f;

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
        return RotationState.isActived()
            && RotationState.getPriority() == SCAFFOLD_MOVE_FIX_PRIORITY;
    }

    private static boolean isLocalPlayer(Object player) {
        Object local = McAccess.thePlayer();
        return local != null && player == local;
    }
}
