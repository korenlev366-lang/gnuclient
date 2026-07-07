package gnu.client.runtime;

import gnu.client.runtime.mc.McAccess;

/**
 * OpenMyau {@code MixinEntityLivingBase} fallback — primary yaw swap is
 * {@link MoveFixHook} around {@code EntityPlayerSP.onLivingUpdate}.
 */
public final class EntityLivingBaseHook {

    private static final String FIELD_ROTATION_YAW = "field_70177_z";

    private EntityLivingBaseHook() {}

    public static void moveFlying(Object entity, float strafe, float forward, float friction) {
        if (entity == null)
            return;
        if (!MoveFixHook.shouldUseServerMoveYaw() || !isLocalPlayer(entity)) {
            invokeMoveFlying(entity, strafe, forward, friction);
            return;
        }

        float savedYaw = McAccess.getFloat(entity, FIELD_ROTATION_YAW);
        McAccess.setFloat(entity, FIELD_ROTATION_YAW, PlayerUpdateHook.silentYawForMoveFix());
        invokeMoveFlying(entity, strafe, forward, friction);
        McAccess.setFloat(entity, FIELD_ROTATION_YAW, savedYaw);
    }

    private static boolean isLocalPlayer(Object entity) {
        Object local = McAccess.thePlayer();
        return local != null && entity == local;
    }

    private static void invokeMoveFlying(Object entity, float strafe, float forward, float friction) {
        McAccess.invoke(entity, "func_70060_a",
            new Class<?>[] {float.class, float.class, float.class},
            strafe, forward, friction);
    }
}
