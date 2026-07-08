package gnu.client.module.modules.combat;

import gnu.client.runtime.mc.McAccess;

/**
 * Raven-bS {@code RotationUtils} subset for AimAssist (reflection-only).
 * Faithful to ctw.dll — no humanization extras.
 */
final class RavenRotationUtils {

    private static final float FAR_THRESHOLD = 180.0f;

    private RavenRotationUtils() {}

    static float wrapAngleTo180(float angle) {
        Class<?> math = McAccess.gameClass("net.minecraft.util.MathHelper");
        if (math == null)
            return angle;
        Object r = McAccess.invokeStatic(math, "func_76142_g", new Class<?>[] { float.class }, angle);
        return r instanceof Float ? (Float) r : angle;
    }

    static float clampPitch(float pitch) {
        Class<?> math = McAccess.gameClass("net.minecraft.util.MathHelper");
        if (math == null)
            return Math.max(-90.0f, Math.min(90.0f, pitch));
        Object r = McAccess.invokeStatic(math, "func_76131_a", new Class<?>[] { float.class, float.class, float.class },
                pitch, -90.0f, 90.0f);
        return r instanceof Float ? (Float) r : pitch;
    }

    static float angleToEntity(Object entity) {
        Object player = McAccess.thePlayer();
        if (player == null || entity == null)
            return 0.0f;
        return (float) (Math.atan2(
                McAccess.entityPosX(entity) - McAccess.entityPosX(player),
                McAccess.entityPosZ(entity) - McAccess.entityPosZ(player)) * 57.2957795 * -1.0);
    }

    static boolean inFov(float originYaw, float fov, float targetAngle) {
        float half = fov * 0.5f;
        double diff = wrapAngleTo180(originYaw - targetAngle);
        if (diff > 0.0)
            return diff <= half;
        return diff >= -half;
    }

    static double distanceSqFromEyeToClosestOnAabb(Object entity) {
        double[] eye = eyePos(McAccess.thePlayer(), 1.0f);
        Object bb = expandedBox(entity);
        if (eye == null || bb == null)
            return Double.MAX_VALUE;
        if (isVecInside(bb, eye[0], eye[1], eye[2]))
            return 0.0;
        double[] closest = closestPointOnAabb(eye[0], eye[1], eye[2], bb);
        double dx = eye[0] - closest[0];
        double dy = eye[1] - closest[1];
        double dz = eye[2] - closest[2];
        return dx * dx + dy * dy + dz * dz;
    }

    /** OpenMyau {@code RotationUtil.angleToEntity} — eye inside expanded AABB → always in FOV. */
    static boolean isEyeInsideExpandedHitbox(Object entity) {
        double[] eye = eyePos(McAccess.thePlayer(), 1.0f);
        Object bb = expandedBox(entity);
        return eye != null && bb != null && isVecInside(bb, eye[0], eye[1], eye[2]);
    }

    static double aimDifference(Object entity, float baseYaw) {
        return ((baseYaw - angleToEntity(entity)) % 360.0 + 540.0) % 360.0 - 180.0;
    }

    static double pitchDifference(Object entity, float basePitch) {
        double[] eye = eyePos(McAccess.thePlayer(), 1.0f);
        Object bb = expandedBox(entity);
        if (eye == null || bb == null)
            return 180.0;
        double minY = McAccess.getDouble(bb, "field_72338_b");
        double maxY = McAccess.getDouble(bb, "field_72337_e");
        double centerY = (minY + maxY) * 0.5;
        double dx = McAccess.entityPosX(entity) - eye[0];
        double dz = McAccess.entityPosZ(entity) - eye[2];
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float targetPitch = (float) (-(Math.atan2(centerY - eye[1], horiz) * 57.2957795));
        return ((basePitch - targetPitch) % 360.0 + 540.0) % 360.0 - 180.0;
    }

    /** Raven-bS {@code smoothRotation} — legacy 1–30 speed scale. */
    static float[] smoothRotation(float baseYaw, float basePitch,
            float targetYaw, float targetPitch,
            int speed, float randomizationPercent) {
        float smoothedYaw = smoothAxisRaven(baseYaw, targetYaw, speed, randomizationPercent, true);
        float smoothedPitch = smoothAxisRaven(basePitch, targetPitch, speed, randomizationPercent, false);
        return fixRotation(smoothedYaw, smoothedPitch, baseYaw, basePitch);
    }

    /** Timewarp Normal mode — separate H/V steps as percent-of-target per tick (0–100). */
    static float[] smoothRotationHv(float baseYaw, float basePitch,
            float targetYaw, float targetPitch,
            int hSpeed, int vSpeed, float randomizationPercent) {
        float smoothedYaw = smoothAxisPercent(baseYaw, targetYaw, hSpeed, randomizationPercent, true);
        float smoothedPitch = smoothAxisPercent(basePitch, targetPitch, vSpeed, randomizationPercent, false);
        return fixRotation(smoothedYaw, smoothedPitch, baseYaw, basePitch);
    }

    /** Timewarp Advanced mode — direct H/V interpolation with GCD snap. */
    static float[] advancedRotationHv(float baseYaw, float basePitch,
            float targetYaw, float targetPitch,
            int hSpeed, int vSpeed, float randomizationPercent) {
        float deltaYaw = wrapAngleTo180(targetYaw - baseYaw);
        float deltaPitch = targetPitch - basePitch;
        float jitter = randomizationMultiplier(randomizationPercent);
        float tH = Math.min(1.0f, Math.max(0.0f, hSpeed / 100.0f) * jitter);
        float tV = Math.min(1.0f, Math.max(0.0f, vSpeed / 100.0f) * jitter);
        float newYaw = baseYaw + deltaYaw * tH;
        float newPitch = basePitch + deltaPitch * tV;
        return fixRotation(newYaw, newPitch, baseYaw, basePitch);
    }

    /**
     * Timewarp: speed is 0–100 (% of remaining angle applied per tick).
     * Pure percent model — no proximity slowdown, no humanization.
     */
    private static float smoothAxisPercent(float base, float target, int speedPercent,
            float randomizationPercent, boolean yaw) {
        if (speedPercent <= 0)
            return yaw ? base : clampPitch(base);

        float delta = yaw ? wrapAngleTo180(target - base) : (target - base);
        float magnitude = Math.abs(delta);
        if (magnitude < 0.001f)
            return yaw ? target : clampPitch(target);

        float t = Math.min(1.0f, speedPercent / 100.0f);
        t *= randomizationMultiplier(randomizationPercent);

        float smoothed = base + delta * t;
        return yaw ? smoothed : clampPitch(smoothed);
    }

    /** Raven-bS legacy: speed 1–30, quadratic step up to 180 deg/tick at 30. */
    private static float smoothAxisRaven(float base, float target, int speed,
            float randomizationPercent, boolean yaw) {
        if (speed <= 0)
            return yaw ? base : clampPitch(base);

        float delta = yaw ? wrapAngleTo180(target - base) : (target - base);
        float magnitude = Math.abs(delta);
        if (magnitude < 0.001f)
            return yaw ? target : clampPitch(target);

        float smoothed = target;
        if (speed < 30) {
            float t = speed / 30.0f;
            float stepSize = t * t * 180.0f;
            stepSize *= randomizationMultiplier(randomizationPercent);

            float stepLength = Math.min(stepSize, magnitude);
            float scale = stepLength / magnitude;
            smoothed = base + delta * scale;
        }
        return yaw ? smoothed : clampPitch(smoothed);
    }

    static float randomizationMultiplier(float randomizationPercent) {
        float range = 0.6f * (randomizationPercent / 100.0f);
        if (range <= 0.001f)
            return 1.0f;
        return 1.0f - range / 2.0f + (float) (Math.random() * range);
    }

    static float unwrapYaw(float yaw, float prevYaw) {
        return prevYaw + ((((yaw - prevYaw + 180.0f) % 360.0f) + 360.0f) % 360.0f - 180.0f);
    }

    /** Raven-bS {@code fixRotation} — per-tick delta is a multiple of sensitivity GCD. */
    static float[] fixRotation(float targetYaw, float targetPitch, float yaw, float pitch) {
        targetYaw = unwrapYaw(targetYaw, yaw);
        float deltaYaw = targetYaw - yaw;
        float deltaPitch = targetPitch - pitch;
        double gcd = McAccess.getMouseSensitivityGcd();
        if (gcd <= 0.0)
            return new float[] { targetYaw, clampPitch(targetPitch) };

        float qYaw = (float) (Math.round(deltaYaw / gcd) * gcd);
        float qPitch = (float) (Math.round(deltaPitch / gcd) * gcd);
        return new float[] { yaw + qYaw, clampPitch(pitch + qPitch) };
    }

    static float[] getRotationsToTarget(Object entity, int speed,
            double horizontalMultipoint, double verticalMultipoint,
            float randomizationPercent, boolean useBackup, double range,
            boolean allowThroughBlocks, boolean allowThroughEntities) {
        return getRotationsToTargetTw(entity, speed, speed, false,
                horizontalMultipoint, verticalMultipoint, randomizationPercent,
                useBackup, range, allowThroughBlocks, allowThroughEntities);
    }

    static float[] getRotationsToTargetTw(Object entity, int hSpeed, int vSpeed, boolean advanced,
            double horizontalMultipoint, double verticalMultipoint,
            float randomizationPercent, boolean useBackup, double range,
            boolean allowThroughBlocks, boolean allowThroughEntities) {
        Object player = McAccess.thePlayer();
        if (player == null || entity == null)
            return null;

        float baseYaw = McAccess.getFloat(player, "field_70177_z");
        float basePitch = McAccess.getFloat(player, "field_70125_A");
        float[] target = useBackup
                ? getRotationsWithBackup(entity, horizontalMultipoint, verticalMultipoint,
                        baseYaw, basePitch, range, allowThroughBlocks, allowThroughEntities)
                : getRotations(entity, horizontalMultipoint, verticalMultipoint, baseYaw, basePitch);
        if (target == null)
            return null;
        if (advanced)
            return advancedRotationHv(baseYaw, basePitch, target[0], target[1], hSpeed, vSpeed, randomizationPercent);
        return smoothRotationHv(baseYaw, basePitch, target[0], target[1], hSpeed, vSpeed, randomizationPercent);
    }

    /** Raw unsmoothed rotation to entity aim point — no speed, no randomization, no smoothing. */
    static float[] getRawRotationsToTarget(Object entity,
            double horizontalMultipoint, double verticalMultipoint,
            boolean useBackup, double range,
            boolean allowThroughBlocks, boolean allowThroughEntities) {
        Object player = McAccess.thePlayer();
        if (player == null || entity == null)
            return null;
        float baseYaw = McAccess.getFloat(player, "field_70177_z");
        float basePitch = McAccess.getFloat(player, "field_70125_A");
        return useBackup
                ? getRotationsWithBackup(entity, horizontalMultipoint, verticalMultipoint,
                        baseYaw, basePitch, range, allowThroughBlocks, allowThroughEntities)
                : getRotations(entity, horizontalMultipoint, verticalMultipoint, baseYaw, basePitch);
    }

    static float[] getRotations(Object entity, double horizontalMultipoint, double verticalMultipoint,
            float baseYaw, float basePitch) {
        double[] aim = getAimPoint(entity, horizontalMultipoint, verticalMultipoint);
        if (aim == null)
            return null;
        return getRotationsToPoint(aim[0], aim[1], aim[2], baseYaw, basePitch);
    }

    static float[] getRotationsToPoint(double x, double y, double z, float baseYaw, float basePitch) {
        Object player = McAccess.thePlayer();
        if (player == null)
            return null;

        double deltaX = x - McAccess.entityPosX(player);
        double deltaZ = z - McAccess.entityPosZ(player);
        double deltaY = y - (McAccess.entityPosY(player) + eyeHeight(player));
        double horizDistSq = deltaX * deltaX + deltaZ * deltaZ;

        float yaw;
        float targetPitch;
        if (horizDistSq < 1.0E-12) {
            yaw = baseYaw;
            targetPitch = (float) (-(Math.atan2(deltaY, 0.0) * 57.2957795));
        } else {
            float targetYaw = (float) (Math.atan2(deltaZ, deltaX) * 57.2957795) - 90.0f;
            yaw = baseYaw + wrapAngleTo180(targetYaw - baseYaw);
            double horizDist = Math.sqrt(horizDistSq);
            targetPitch = (float) (-(Math.atan2(deltaY, horizDist) * 57.2957795));
        }

        float pitch = basePitch + wrapAngleTo180(targetPitch - basePitch);
        return new float[] { yaw, clampPitch(pitch) };
    }

    static double[] getAimPoint(Object entity, double horizontalMultipoint, double verticalMultipoint) {
        Object player = McAccess.thePlayer();
        Object bb = expandedBox(entity);
        if (player == null || bb == null)
            return null;

        double minX = McAccess.getDouble(bb, "field_72340_a");
        double minY = McAccess.getDouble(bb, "field_72338_b");
        double maxY = McAccess.getDouble(bb, "field_72337_e");
        double minZ = McAccess.getDouble(bb, "field_72339_c");
        double maxX = McAccess.getDouble(bb, "field_72336_d");
        double maxZ = McAccess.getDouble(bb, "field_72334_f");

        double centerX = (minX + maxX) * 0.5;
        double centerY = McAccess.entityPosY(entity) + eyeHeight(entity);
        double centerZ = (minZ + maxZ) * 0.5;

        double[] eye = eyePos(player, 1.0f);
        if (eye == null)
            return null;

        if (isVecInside(bb, eye[0], eye[1], eye[2]))
            return new double[] { centerX, eye[1], centerZ };

        double[] cl = closestPointOnAabb(eye[0], eye[1], eye[2], bb);
        double tH = Math.max(0.0, Math.min(1.0, horizontalMultipoint / 100.0));
        double tV = Math.max(0.0, Math.min(1.0, verticalMultipoint / 100.0));
        return new double[] {
                centerX + (cl[0] - centerX) * tH,
                centerY + (cl[1] - centerY) * tV,
                centerZ + (cl[2] - centerZ) * tH
        };
    }

    static boolean hasValidAimPoint(Object entity, double hMult, double vMult, double range,
            boolean allowThroughBlocks, boolean allowThroughEntities) {
        double[] main = getAimPoint(entity, hMult, vMult);
        if (main == null)
            return false;
        double[] eye = eyePos(McAccess.thePlayer(), 1.0f);
        if (eye == null)
            return false;
        double dx = main[0] - eye[0];
        double dy = main[1] - eye[1];
        double dz = main[2] - eye[2];
        if (dx * dx + dy * dy + dz * dz < 1.0E-12)
            return true;
        return canAimAtPoint(eye, main, entity, range, allowThroughBlocks, allowThroughEntities);
    }

    static float[] getRotationsWithBackup(Object entity, double horizontalMultipoint, double verticalMultipoint,
            float baseYaw, float basePitch, double range,
            boolean allowThroughBlocks, boolean allowThroughEntities) {
        double[] main = getAimPoint(entity, horizontalMultipoint, verticalMultipoint);
        if (main == null)
            return null;
        double[] eye = eyePos(McAccess.thePlayer(), 1.0f);
        Object bb = expandedBox(entity);
        if (eye == null || bb == null)
            return null;

        if (isVecInside(bb, eye[0], eye[1], eye[2])) {
            double centerX = (McAccess.getDouble(bb, "field_72340_a") + McAccess.getDouble(bb, "field_72336_d")) * 0.5;
            double centerZ = (McAccess.getDouble(bb, "field_72339_c") + McAccess.getDouble(bb, "field_72334_f")) * 0.5;
            return getRotationsToPoint(centerX, eye[1], centerZ, baseYaw, basePitch);
        }

        if (canAimAtPoint(eye, main, entity, range, allowThroughBlocks, allowThroughEntities))
            return getRotationsToPoint(main[0], main[1], main[2], baseYaw, basePitch);

        return getRotations(entity, horizontalMultipoint, verticalMultipoint, baseYaw, basePitch);
    }

    private static boolean canAimAtPoint(double[] eye, double[] point, Object target, double range,
            boolean allowThroughBlocks, boolean allowThroughEntities) {
        double dx = point[0] - eye[0];
        double dy = point[1] - eye[1];
        double dz = point[2] - eye[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-6)
            return false;

        if (!allowThroughBlocks && blockBetween(eye, point))
            return false;
        return allowThroughEntities || !entityBetween(eye, point, target);
    }

    private static boolean blockBetween(double[] eye, double[] point) {
        Object world = McAccess.theWorld();
        if (world == null)
            return false;
        Object start = vec3(eye[0], eye[1], eye[2]);
        Object end = vec3(point[0], point[1], point[2]);
        if (start == null || end == null)
            return false;
        Class<?> vec3 = McAccess.gameClass("net.minecraft.util.Vec3");
        Object hit = McAccess.invoke(world, "func_72933_a", new Class<?>[] { vec3, vec3 }, start, end);
        return hit != null;
    }

    private static boolean entityBetween(double[] eye, double[] point, Object target) {
        Object world = McAccess.theWorld();
        Object player = McAccess.thePlayer();
        if (world == null || player == null || target == null)
            return false;
        return blockBetween(eye, point);
    }

    private static Object expandedBox(Object entity) {
        if (entity == null)
            return null;
        Object box = McAccess.invoke(entity, "func_174813_aQ", new Class<?>[0]);
        if (box == null)
            return null;
        Object border = McAccess.invoke(entity, "func_70111_Y", new Class<?>[0]);
        float b = border instanceof Float ? (Float) border : 0.0f;
        return McAccess.invoke(box, "func_72314_b",
                new Class<?>[] { double.class, double.class, double.class },
                (double) b, (double) b, (double) b);
    }

    private static double[] eyePos(Object player, float partialTicks) {
        if (player == null)
            return null;
        Object vec = McAccess.invoke(player, "func_174824_e", new Class<?>[] { float.class }, partialTicks);
        if (vec == null)
            return null;
        return new double[] {
                McAccess.getDouble(vec, "field_72450_a"),
                McAccess.getDouble(vec, "field_72448_b"),
                McAccess.getDouble(vec, "field_72449_c")
        };
    }

    private static float eyeHeight(Object entity) {
        Object h = McAccess.invoke(entity, "func_70047_e", new Class<?>[0]);
        return h instanceof Float ? (Float) h : 1.62f;
    }

    private static double[] closestPointOnAabb(double x, double y, double z, Object aabb) {
        double minX = McAccess.getDouble(aabb, "field_72340_a");
        double minY = McAccess.getDouble(aabb, "field_72338_b");
        double minZ = McAccess.getDouble(aabb, "field_72339_c");
        double maxX = McAccess.getDouble(aabb, "field_72336_d");
        double maxY = McAccess.getDouble(aabb, "field_72337_e");
        double maxZ = McAccess.getDouble(aabb, "field_72334_f");
        return new double[] {
                clamp(x, minX, maxX),
                clamp(y, minY, maxY),
                clamp(z, minZ, maxZ)
        };
    }

    private static boolean isVecInside(Object aabb, double x, double y, double z) {
        Object vec = vec3(x, y, z);
        if (vec == null || aabb == null)
            return false;
        Object r = McAccess.invoke(aabb, "func_72316_a",
                new Class<?>[] { McAccess.gameClass("net.minecraft.util.Vec3") }, vec);
        return r instanceof Boolean && (Boolean) r;
    }

    private static Object vec3(double x, double y, double z) {
        return McAccess.newInstance("net.minecraft.util.Vec3",
                new Class<?>[] { double.class, double.class, double.class }, x, y, z);
    }

    private static double clamp(double v, double lo, double hi) {
        if (v > hi)
            return hi;
        if (v < lo)
            return lo;
        return v;
    }
}
