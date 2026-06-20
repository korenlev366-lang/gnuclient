package gnu.client.runtime;

import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.modules.visual.FreeLookModule;
import gnu.client.runtime.mc.McAccess;

/**
 * Static hook methods called from ASM-injected INVOKESTATIC inside
 * {@code EntityRenderer.orientCamera} and {@code EntityRenderer.updateCameraAndRender}
 * via {@link WorldRenderTransformer}.
 *
 * <p><strong>dispatchSetAngles</strong> — intercepts
 * {@code EntityPlayerSP.setAngles(yaw, pitch)} called from the render thread.
 * In 1.8.9, setAngles takes DELTAS (mouse movement after sensitivity scaling),
 * NOT absolute target angles. Internally it does rotationYaw += yaw * 0.15
 * and rotationPitch -= pitch * 0.15.
 *
 * <p>The yaw/pitch args are passed directly to
 * {@link FreeLookModule#applyCameraDelta} which replicates the vanilla math
 * to accumulate into the independent cameraYaw/cameraPitch.
 *
 * <p>For ALL other cases (freelook off, non-player entity, null), forwards to
 * the real setAngles via McAccess.invoke — this passthrough is mandatory because
 * every setAngles call in updateCameraAndRender is redirected.
 *
 * <p><strong>redirectYaw/redirectPitch</strong> — replaces every GETFIELD of
 * {@code Entity.rotationYaw/rotationPitch} in orientCamera. Returns the
 * independent cameraYaw/cameraPitch when freelook is active for the local player,
 * otherwise reads and returns the real field value via reflection.
 */
public final class FreeLookHook {

    private static final String FIELD_ROTATION_YAW = "field_70177_z";
    private static final String FIELD_ROTATION_PITCH = "field_70125_A";

    private FreeLookHook() {}

    /**
     * Redirects a read of {@code Entity.rotationYaw} (field_70177_z).
     *
     * <p>Called from ASM-injected INVOKESTATIC for every GETFIELD of
     * rotationYaw on {@code net/minecraft/entity/Entity} inside orientCamera.
     * The entity reference is already on the stack (from the ALOAD that preceded
     * the original GETFIELD).
     *
     * @param entity the Entity whose rotationYaw is being read
     * @return cameraYaw if freelook active for thePlayer, else real field value
     */
    public static float redirectYaw(Object entity) {
        FreeLookModule fl = getActiveFreeLook();
        if (fl != null) {
            Object player = McAccess.thePlayer();
            if (player != null && player == entity) {
                return fl.getCameraYaw();
            }
        }
        // Passthrough: read the real field value from the entity
        if (entity == null) return 0f;
        return McAccess.getFloat(entity, FIELD_ROTATION_YAW);
    }

    /**
     * Redirects a read of {@code Entity.rotationPitch} (field_70125_A).
     *
     * @param entity the Entity whose rotationPitch is being read
     * @return cameraPitch if freelook active for thePlayer, else real field value
     */
    public static float redirectPitch(Object entity) {
        FreeLookModule fl = getActiveFreeLook();
        if (fl != null) {
            Object player = McAccess.thePlayer();
            if (player != null && player == entity) {
                return fl.getCameraPitch();
            }
        }
        if (entity == null) return 0f;
        return McAccess.getFloat(entity, FIELD_ROTATION_PITCH);
    }

    /**
     * Intercepts {@code EntityPlayerSP.setAngles(yaw, pitch)} called inside
     * {@code EntityRenderer.updateCameraAndRender} on the render thread.
     *
     * <p>In 1.8.9, setAngles takes DELTAS, not absolute angles. The yaw/pitch
     * args are the per-frame sensitivity-applied mouse deltas (vanilla already
     * baked invertMouse into the pitch arg). When FreeLook is active for the
     * local player, we pass these deltas directly to
     * {@link FreeLookModule#applyCameraDelta} which replicates vanilla's
     * internal math (rotationYaw += yaw * 0.15, rotationPitch -= pitch * 0.15)
     * to accumulate into the independent cameraYaw/cameraPitch. The real
     * setAngles call is suppressed so the player's actual rotation stays frozen.
     *
     * <p>For ALL other cases, forwards to the real setAngles via McAccess.
     *
     * @param entity the Entity whose rotation is being set (EntityPlayerSP)
     * @param yaw    the per-frame yaw delta (sensitivity-applied, degrees)
     * @param pitch  the per-frame pitch delta (sensitivity-applied, degrees, invert already baked)
     */
    public static void dispatchSetAngles(Object entity, float yaw, float pitch) {
        FreeLookModule fl = getActiveFreeLook();

        Object player = McAccess.thePlayer();

        if (fl != null) {
            if (player != null && player == entity) {
                // yaw/pitch are already deltas (mouse movement scaled by sensitivity).
                // Pass directly to applyCameraDelta which replicates vanilla's
                // rotationYaw += yaw * 0.15, rotationPitch -= pitch * 0.15.
                fl.applyCameraDelta(yaw, pitch);

                // Do NOT call real setAngles — the player's real rotation stays frozen.
                // orientCamera will read the independent camera angles via redirectYaw/redirectPitch.
                return;
            }
        }
        // Passthrough: call the real setAngles on the entity
        McAccess.invoke(entity, "func_70082_c", new Class[]{float.class, float.class}, yaw, pitch);
    }

    /**
     * Returns the FreeLookModule if it is enabled and perspective is active,
     * or null otherwise.
     */
    private static FreeLookModule getActiveFreeLook() {
        Module m = ModuleManager.INSTANCE.getModule("FreeLook");
        if (!(m instanceof FreeLookModule)) return null;
        FreeLookModule fl = (FreeLookModule) m;
        if (!fl.isEnabled() || !fl.isPerspectiveActive()) return null;
        return fl;
    }
}
