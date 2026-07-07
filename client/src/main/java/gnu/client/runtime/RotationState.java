package gnu.client.runtime;

/**
 * OpenMyau {@code RotationState} — tracks silent rotation activation and the yaw
 * sent in C03 / used by movefix ({@code getSmoothedYaw}).
 */
public final class RotationState {

    private static int state = -1;
    private static float smoothYaw;
    private static int priority = -1;

    private RotationState() {}

    public static void applyState(boolean active, float yaw, float pitch, float pervYaw, int rotPriority) {
        state = active ? 0 : state + 1;
        smoothYaw = pervYaw;
        priority = rotPriority;
    }

    public static boolean isActived() {
        return state >= 0 && state <= 0;
    }

    public static float getSmoothedYaw() {
        return smoothYaw;
    }

    public static float getPriority() {
        return priority;
    }

    public static void reset() {
        state = -1;
        priority = -1;
    }
}
