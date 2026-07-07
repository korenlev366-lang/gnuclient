package gnu.client.runtime;

import gnu.client.module.modules.movement.StasisModule;
import gnu.client.module.modules.player.ScaffoldModule;
import gnu.client.runtime.mc.McAccess;

/**
 * JVMTI hooks around {@code EntityPlayerSP.onUpdate}.
 *
 * <p>OpenMyau-style silent rotations are applied by temporarily writing the
 * requested yaw/pitch immediately before vanilla sends its walking packet, then
 * restoring the local camera rotation at {@code onUpdate} return.
 */
public final class PlayerUpdateHook {

    private static boolean overrideActive;
    private static float overrideYaw;
    private static float overridePitch;
    private static boolean pendingRestore;
    private static float pendingYaw;
    private static float pendingPitch;

    private PlayerUpdateHook() {}

    /**
     * @return true to skip the rest of {@code onUpdate} (raven {@code CallbackInfo.cancel}).
     */
    public static boolean onUpdateHead(Object player) {
        if (player == null)
            return false;
        Object local = McAccess.thePlayer();
        if (local == null || player != local)
            return false;

        clearRotationOverride();
        StasisModule.onPreUpdate(player);
        ScaffoldModule.onPreUpdate(player);
        return false;
    }

    public static void requestRotation(float yaw, float pitch) {
        overrideYaw = yaw;
        overridePitch = clampPitch(pitch);
        overrideActive = true;
    }

    /** Silent yaw for movefix physics — prefer the rotation queued for this tick. */
    public static float silentYawForMoveFix() {
        if (overrideActive)
            return overrideYaw;
        return RotationState.getSmoothedYaw();
    }

    public static float lastReportedYaw(Object player) {
        return McAccess.getFloat(player, "field_175164_bL");
    }

    public static float lastReportedPitch(Object player) {
        return McAccess.getFloat(player, "field_175165_bM");
    }

    public static void beforeWalkingPlayer(Object player) {
        if (!isLocal(player))
            return;
        if (overrideActive)
            beginRotationSwap(player);
        ScaffoldModule.onBeforeWalkingPlace(player);
    }

    /** After {@code onUpdateWalkingPlayer} — reserved for post-walking hooks. */
    public static void onAfterWalkingPlayer(Object player) {
    }

    /** Apply requested silent rotation to the local player before block placement. */
    public static void ensureRotationApplied(Object player) {
        beginRotationSwap(player);
    }

    private static void beginRotationSwap(Object player) {
        if (!isLocal(player) || !overrideActive)
            return;
        if (!pendingRestore) {
            pendingYaw = McAccess.getFloat(player, "field_70177_z");
            pendingPitch = McAccess.getFloat(player, "field_70125_A");
            pendingRestore = true;
        }
        McAccess.setFloat(player, "field_70177_z", overrideYaw);
        McAccess.setFloat(player, "field_70125_A", overridePitch);
    }

    public static void onUpdateReturn(Object player) {
        if (!isLocal(player)) {
            clearRotationOverride();
            return;
        }
        if (pendingRestore) {
            float sentYaw = McAccess.getFloat(player, "field_70177_z");
            float sentPitch = McAccess.getFloat(player, "field_70125_A");
            McAccess.setFloat(player, "field_175164_bL", sentYaw);
            McAccess.setFloat(player, "field_175165_bM", sentPitch);

            float restoredYaw = sentYaw + wrapAngle(pendingYaw - sentYaw);
            McAccess.setFloat(player, "field_70177_z", restoredYaw);
            McAccess.setFloat(player, "field_70125_A", pendingPitch);
            McAccess.setFloat(player, "field_70126_B", restoredYaw);
            McAccess.setFloat(player, "field_70127_C", pendingPitch);
        }
        clearRotationOverride();
    }

    private static boolean isLocal(Object player) {
        if (player == null)
            return false;
        Object local = McAccess.thePlayer();
        return local != null && player == local;
    }

    private static void clearRotationOverride() {
        overrideActive = false;
        pendingRestore = false;
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90.0f, Math.min(90.0f, pitch));
    }

    private static float wrapAngle(float angle) {
        angle %= 360.0f;
        if (angle >= 180.0f)
            angle -= 360.0f;
        if (angle < -180.0f)
            angle += 360.0f;
        return angle;
    }

}
