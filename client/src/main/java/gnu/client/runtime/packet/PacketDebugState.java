package gnu.client.runtime.packet;

import gnu.client.common.GnuLog;
import gnu.client.runtime.mc.McAccess;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight packet-derived position cache for diagnostics visuals.
 */
public final class PacketDebugState {

    public static final class Vec3 {
        public final double x;
        public final double y;
        public final double z;

        public Vec3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static volatile boolean selfValid;
    private static volatile double selfX;
    private static volatile double selfY;
    private static volatile double selfZ;
    private static volatile int activeTargetId = -1;

    private static final Map<Integer, Vec3> TARGET_REAL = new ConcurrentHashMap<>();
    private static final Map<Integer, Vec3> TARGET_BACKTRACKED = new ConcurrentHashMap<>();

    private PacketDebugState() {}

    public static void captureSentPlayerPacket(Object packet) {
        if (!PacketHelper.isPlayerMovement(packet))
            return;

        boolean hasPos = movementHasPosition(packet);
        if (!hasPos)
            return;

        selfX = packetX(packet);
        selfY = packetY(packet);
        selfZ = packetZ(packet);
        selfValid = true;
        GnuLog.log("PKT_DEBUG self C03 captured x=" + selfX + " y=" + selfY + " z=" + selfZ);
    }

    public static boolean hasSelfPosition() {
        return selfValid;
    }

    public static Vec3 selfPosition() {
        if (!selfValid)
            return null;
        return new Vec3(selfX, selfY, selfZ);
    }

    public static void setActiveTarget(int entityId) {
        activeTargetId = entityId;
    }

    public static int activeTargetId() {
        return activeTargetId;
    }

    public static void clearTarget(int entityId) {
        TARGET_REAL.remove(entityId);
        TARGET_BACKTRACKED.remove(entityId);
        if (activeTargetId == entityId)
            activeTargetId = -1;
    }

    public static void clearAllTargets() {
        TARGET_REAL.clear();
        TARGET_BACKTRACKED.clear();
        activeTargetId = -1;
    }

    public static void updateTargetBacktracked(int entityId, double x, double y, double z) {
        if (entityId < 0)
            return;
        TARGET_BACKTRACKED.put(entityId, new Vec3(x, y, z));
    }

    public static void updateTargetReal(int entityId, double x, double y, double z) {
        if (entityId < 0)
            return;
        TARGET_REAL.put(entityId, new Vec3(x, y, z));
    }

    public static Vec3 targetBacktracked(int entityId) {
        return TARGET_BACKTRACKED.get(entityId);
    }

    public static Vec3 targetReal(int entityId) {
        return TARGET_REAL.get(entityId);
    }

    private static boolean movementHasPosition(Object packet) {
        Object moving = McAccess.invoke(packet, "func_149466_j", new Class<?>[0]);
        if (!(moving instanceof Boolean))
            moving = McAccess.invokeNamed(packet, "isMoving", new Class<?>[0]);
        if (moving instanceof Boolean)
            return (Boolean) moving;
        return true;
    }

    private static double packetX(Object packet) {
        Object value = McAccess.invoke(packet, "func_149464_c", new Class<?>[0]);
        if (!(value instanceof Number))
            value = McAccess.invokeNamed(packet, "getPositionX", new Class<?>[0]);
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }

    private static double packetY(Object packet) {
        Object value = McAccess.invoke(packet, "func_149467_d", new Class<?>[0]);
        if (!(value instanceof Number))
            value = McAccess.invokeNamed(packet, "getPositionY", new Class<?>[0]);
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }

    private static double packetZ(Object packet) {
        Object value = McAccess.invoke(packet, "func_149472_e", new Class<?>[0]);
        if (!(value instanceof Number))
            value = McAccess.invokeNamed(packet, "getPositionZ", new Class<?>[0]);
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }
}
