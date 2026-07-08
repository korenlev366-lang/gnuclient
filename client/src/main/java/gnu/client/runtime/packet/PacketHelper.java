package gnu.client.runtime.packet;

import gnu.client.runtime.mc.McAccess;

/**
 * Packet type checks without compile-time net.minecraft.* references.
 */
public final class PacketHelper {

    private PacketHelper() {}

    public static boolean isPacket(Object obj) {
        if (obj == null)
            return false;
        Class<?> c = obj.getClass();
        while (c != null) {
            String name = c.getName();
            if (name.contains("network.Packet") || name.endsWith(".Packet"))
                return true;
            for (Class<?> iface : c.getInterfaces()) {
                if (iface.getName().contains("network.Packet"))
                    return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    public static boolean isEntityVelocity(Object packet) {
        return classNameContains(packet, "S12PacketEntityVelocity");
    }

    public static boolean isEntityRelMove(Object packet) {
        return classNameContains(packet, "S14PacketEntity");
    }

    /** {@code S14} rel-move / look-move only — not rotation-only {@code S16}. */
    public static boolean isEntityPositionUpdate(Object packet) {
        if (isEntityTeleport(packet))
            return true;
        if (!isEntityRelMove(packet))
            return false;
        String name = packet.getClass().getName();
        if (name.contains("S16PacketEntityLook") || name.contains("S16PacketEntity"))
            return false;
        if (name.contains("EntityLook") && !name.contains("LookMove"))
            return false;
        return true;
    }

    public static boolean isEntityTeleport(Object packet) {
        return classNameContains(packet, "S18PacketEntityTeleport");
    }

    /** S14 relative X delta in blocks (fixed-point / 32). */
    public static double s14DeltaX(Object packet) {
        return readFixedPointDelta(packet, "func_149062_c", "getX");
    }

    public static double s14DeltaY(Object packet) {
        return readFixedPointDelta(packet, "func_149061_d", "getY");
    }

    public static double s14DeltaZ(Object packet) {
        return readFixedPointDelta(packet, "func_149064_e", "getZ");
    }

    /** S18 absolute block coords (fixed-point / 32). */
    public static double s18PosX(Object packet) {
        return readFixedPointAbsolute(packet, "field_149456_a", "func_149449_c", "getX");
    }

    public static double s18PosY(Object packet) {
        return readFixedPointAbsolute(packet, "field_149454_b", "func_149448_d", "getY");
    }

    public static double s18PosZ(Object packet) {
        return readFixedPointAbsolute(packet, "field_149455_c", "func_149447_e", "getZ");
    }

    private static double readFixedPointDelta(Object packet, String getterSrg, String getterNamed) {
        Object value = McAccess.invoke(packet, getterSrg, new Class<?>[0]);
        if (!(value instanceof Number))
            value = McAccess.invokeNamed(packet, getterNamed, new Class<?>[0]);
        if (value instanceof Byte)
            return ((Byte) value).doubleValue() / 32.0;
        return value instanceof Number ? ((Number) value).doubleValue() / 32.0 : 0.0;
    }

    private static double readFixedPointAbsolute(Object packet, String fieldSrg, String getterSrg, String getterNamed) {
        Object value = McAccess.invoke(packet, getterSrg, new Class<?>[0]);
        if (!(value instanceof Number))
            value = McAccess.invokeNamed(packet, getterNamed, new Class<?>[0]);
        if (value instanceof Number)
            return ((Number) value).doubleValue() / 32.0;
        return McAccess.getInt(packet, fieldSrg) / 32.0;
    }

    public static boolean isPlayerMovement(Object packet) {
        return isInstanceOfGameClass(packet, "net.minecraft.network.play.client.C03PacketPlayer")
                || classNameContains(packet, "C03PacketPlayer");
    }

    public static double c03PosX(Object packet) {
        if (!c03HasPosition(packet))
            return Double.NaN;
        Object value = McAccess.invoke(packet, "func_149464_c", new Class<?>[0]);
        if (!(value instanceof Number))
            value = McAccess.invokeNamed(packet, "getPositionX", new Class<?>[0]);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    public static double c03PosY(Object packet) {
        if (!c03HasPosition(packet))
            return Double.NaN;
        Object value = McAccess.invoke(packet, "func_149467_d", new Class<?>[0]);
        if (!(value instanceof Number))
            value = McAccess.invokeNamed(packet, "getPositionY", new Class<?>[0]);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    public static double c03PosZ(Object packet) {
        if (!c03HasPosition(packet))
            return Double.NaN;
        Object value = McAccess.invoke(packet, "func_149472_e", new Class<?>[0]);
        if (!(value instanceof Number))
            value = McAccess.invokeNamed(packet, "getPositionZ", new Class<?>[0]);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    /** Squared distance between a position C03 and the live player feet position. */
    public static double c03PositionDistSqToPlayer(Object packet, Object player) {
        if (player == null || !c03HasPosition(packet))
            return Double.POSITIVE_INFINITY;
        double px = McAccess.getDouble(player, "field_70165_t");
        double py = McAccess.getDouble(player, "field_70163_u");
        double pz = McAccess.getDouble(player, "field_70161_v");
        double x = c03PosX(packet);
        double y = c03PosY(packet);
        double z = c03PosZ(packet);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z))
            return Double.POSITIVE_INFINITY;
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** True for C04/C06 (or {@code isMoving}) — rotation-only C03 must not be queued (Simulation). */
    public static boolean c03HasPosition(Object packet) {
        if (!isPlayerMovement(packet))
            return false;
        String name = packet.getClass().getName();
        if (name.contains("C04Packet") || name.contains("C06Packet"))
            return true;
        Object moving = McAccess.invoke(packet, "func_149466_j", new Class<?>[0]);
        if (!(moving instanceof Boolean))
            moving = McAccess.invokeNamed(packet, "isMoving", new Class<?>[0]);
        return moving instanceof Boolean && (Boolean) moving;
    }

    /** {@code C03PacketPlayer.onGround} — false while jumping/falling. */
    public static boolean c03OnGround(Object packet) {
        if (!isPlayerMovement(packet))
            return true;
        Object r = McAccess.invokeNamed(packet, "isOnGround", new Class<?>[0]);
        if (r instanceof Boolean)
            return (Boolean) r;
        r = McAccess.invoke(packet, "func_149465_i", new Class<?>[0]);
        return !(r instanceof Boolean) || (Boolean) r;
    }

    /** SRG fields on {@code C03PacketPlayer} (MCP 1.8.9 stable_22). */
    private static final String FIELD_C03_X = "field_149479_a";
    private static final String FIELD_C03_Y = "field_149477_b";
    private static final String FIELD_C03_Z = "field_149478_c";
    private static final String FIELD_C03_YAW = "field_149476_e";
    private static final String FIELD_C03_PITCH = "field_149473_f";
    private static final String FIELD_C03_ON_GROUND = "field_149474_g";
    private static final String FIELD_C03_ROTATING = "field_149481_i";

    public static void c03SetPosition(Object packet, double x, double y, double z) {
        if (!c03HasPosition(packet))
            return;
        McAccess.setDouble(packet, FIELD_C03_X, x);
        McAccess.setDouble(packet, FIELD_C03_Y, y);
        McAccess.setDouble(packet, FIELD_C03_Z, z);
    }

    public static void c03SetOnGround(Object packet, boolean onGround) {
        if (!isPlayerMovement(packet))
            return;
        McAccess.setBool(packet, FIELD_C03_ON_GROUND, onGround);
    }

    public static boolean c03HasRotation(Object packet) {
        if (!isPlayerMovement(packet))
            return false;
        String name = packet.getClass().getName();
        if (name.contains("C05Packet") || name.contains("C06Packet"))
            return true;
        Object rotating = McAccess.invoke(packet, "func_149463_k", new Class<?>[0]);
        if (!(rotating instanceof Boolean))
            rotating = McAccess.invokeNamed(packet, "isRotating", new Class<?>[0]);
        return rotating instanceof Boolean && (Boolean) rotating;
    }

    public static float c03Yaw(Object packet) {
        if (!isPlayerMovement(packet))
            return 0.0f;
        Object value = McAccess.invoke(packet, "func_149462_g", new Class<?>[0]);
        if (!(value instanceof Number))
            value = McAccess.invokeNamed(packet, "getYaw", new Class<?>[0]);
        return value instanceof Number ? ((Number) value).floatValue() : McAccess.getFloat(packet, FIELD_C03_YAW);
    }

    public static float c03Pitch(Object packet) {
        if (!isPlayerMovement(packet))
            return 0.0f;
        Object value = McAccess.invoke(packet, "func_149470_h", new Class<?>[0]);
        if (!(value instanceof Number))
            value = McAccess.invokeNamed(packet, "getPitch", new Class<?>[0]);
        return value instanceof Number ? ((Number) value).floatValue() : McAccess.getFloat(packet, FIELD_C03_PITCH);
    }

    public static void c03SetRotation(Object packet, float yaw, float pitch) {
        if (!isPlayerMovement(packet))
            return;
        McAccess.setFloat(packet, FIELD_C03_YAW, yaw);
        McAccess.setFloat(packet, FIELD_C03_PITCH, pitch);
        McAccess.setBool(packet, FIELD_C03_ROTATING, true);
    }

    public static void c03SetYaw(Object packet, float yaw) {
        if (!isPlayerMovement(packet))
            return;
        McAccess.setFloat(packet, FIELD_C03_YAW, yaw);
        McAccess.setBool(packet, FIELD_C03_ROTATING, true);
    }

    public static void c03SetPitch(Object packet, float pitch) {
        if (!isPlayerMovement(packet))
            return;
        McAccess.setFloat(packet, FIELD_C03_PITCH, pitch);
        McAccess.setBool(packet, FIELD_C03_ROTATING, true);
    }

    public static int entityId(Object packet) {
        Object id = McAccess.invoke(packet, "func_149026_a", new Class<?>[0]);
        if (id == null)
            id = McAccess.invokeNamed(packet, "getEntityID", new Class<?>[0]);
        if (id instanceof Integer)
            return (Integer) id;
        return -1;
    }

    /** Entity id on inbound play packets (S12/S14/S18/S19/S1C, etc.). */
    public static int packetEntityId(Object packet) {
        return packetEntityIdInWorld(packet, null);
    }

    /**
     * Entity id for inbound packets. {@code S14PacketEntity} has no int id field —
     * resolve via {@code getEntity(world)} (BetterPing / OpenMyau pattern).
     */
    public static int packetEntityIdInWorld(Object packet, Object world) {
        if (packet == null)
            return -1;
        int id = McAccess.getInt(packet, "field_149074_a");
        if (id > 0)
            return id;
        id = McAccess.getInt(packet, "field_149164_a");
        if (id > 0)
            return id;
        id = McAccess.getInt(packet, "field_149451_a");
        if (id > 0)
            return id;
        Object idObj = McAccess.invokeNamed(packet, "getEntityId", new Class<?>[0]);
        if (idObj instanceof Integer && (Integer) idObj > 0)
            return (Integer) idObj;
        idObj = McAccess.invokeNamed(packet, "getEntityID", new Class<?>[0]);
        if (idObj instanceof Integer && (Integer) idObj > 0)
            return (Integer) idObj;
        if (isEntityVelocity(packet))
            return velocityEntityId(packet);
        if (world != null && isEntityRelMove(packet)) {
            Object entity = McAccess.invoke(packet, "func_149064_a", new Class<?>[] { world.getClass() }, world);
            if (entity == null)
                entity = McAccess.invoke(packet, "func_149052_a", new Class<?>[] { world.getClass() }, world);
            if (entity == null)
                entity = McAccess.invokeNamed(packet, "getEntity", new Class<?>[] { world.getClass() }, world);
            id = McAccess.entityId(entity);
            if (id > 0)
                return id;
        }
        return -1;
    }

    public static boolean isEntityMetadata(Object packet) {
        return classNameContains(packet, "S1CPacketEntityMetadata");
    }

    public static boolean isDestroyEntities(Object packet) {
        return classNameContains(packet, "S13PacketDestroyEntities");
    }

    /** Inbound packets that must reach the client immediately during Backtrack. */
    public static boolean isBacktrackPassThrough(Object packet) {
        if (packet == null)
            return true;
        if (isInboundQueueExempt(packet))
            return true;
        if (isEntityVelocity(packet) || isAnimationPacket(packet))
            return true;
        if (isEntityStatus(packet) || isEntityMetadata(packet) || isDestroyEntities(packet))
            return true;
        if (classNameContains(packet, "S29PacketSoundEffect"))
            return true;
        return false;
    }

    /** Only S14/S18 movement for the active backtrack target may be queued. */
    public static boolean isBacktrackQueueCandidate(Object packet, int targetEntityId) {
        return isBacktrackQueueCandidate(packet, targetEntityId, null);
    }

    /** @param world required for reliable {@code S14} entity-id resolution */
    public static boolean isBacktrackQueueCandidate(Object packet, int targetEntityId, Object world) {
        if (packet == null || targetEntityId < 0)
            return false;
        if (!isEntityPositionUpdate(packet))
            return false;
        return packetEntityIdInWorld(packet, world) == targetEntityId;
    }

    public static int velocityEntityId(Object packet) {
        Object id = McAccess.invoke(packet, "func_149412_c", new Class<?>[0]);
        if (id == null)
            id = McAccess.invokeNamed(packet, "getEntityID", new Class<?>[0]);
        if (id instanceof Integer)
            return (Integer) id;
        return -1;
    }

    /** S12 motion ints are velocity × 8000 (1.8.9). */
    public static final int MIN_MELEE_KB_HORIZONTAL_MOTION = 80;

    private static volatile long lastInboundExplosionAtMs;

    public static void noteInboundExplosion() {
        lastInboundExplosionAtMs = System.currentTimeMillis();
    }

    public static boolean isRecentExplosionKnockback() {
        return System.currentTimeMillis() - lastInboundExplosionAtMs < 100L;
    }

    public static int velocityMotionX(Object packet) {
        return readVelocityMotionComponent(packet, "field_149415_b", "func_149271_f");
    }

    public static int velocityMotionY(Object packet) {
        return readVelocityMotionComponent(packet, "field_149416_c", "func_149272_g");
    }

    public static int velocityMotionZ(Object packet) {
        return readVelocityMotionComponent(packet, "field_149414_d", "func_149269_i");
    }

    public static void velocitySetMotionX(Object packet, int motionX) {
        if (!isEntityVelocity(packet))
            return;
        McAccess.setInt(packet, "field_149415_b", motionX);
    }

    public static void velocitySetMotionY(Object packet, int motionY) {
        if (!isEntityVelocity(packet))
            return;
        McAccess.setInt(packet, "field_149416_c", motionY);
    }

    public static void velocitySetMotionZ(Object packet, int motionZ) {
        if (!isEntityVelocity(packet))
            return;
        McAccess.setInt(packet, "field_149414_d", motionZ);
    }

    public static boolean isSteerVehicle(Object packet) {
        if (packet == null)
            return false;
        Class<?> c0c = McAccess.gameClass("net.minecraft.network.play.client.C0CPacketInput");
        if (c0c != null && c0c.isInstance(packet))
            return true;
        return classNameContains(packet, "C0CPacketInput");
    }

    /** C0C strafe speed (1.8.9 {@code C0CPacketInput}). */
    public static float steerStrafe(Object packet) {
        return readSteerFloat(packet, "field_149618_a", "func_149616_c", "getStrafeSpeed");
    }

    public static float steerForward(Object packet) {
        return readSteerFloat(packet, "field_149617_b", "func_149614_d", "getForwardSpeed");
    }

    public static boolean steerJump(Object packet) {
        return readSteerBoolean(packet, "field_149619_c", "func_149618_e", "isJumping");
    }

    public static void steerSetStrafe(Object packet, float strafe) {
        if (!isSteerVehicle(packet))
            return;
        McAccess.setFloat(packet, "field_149618_a", strafe);
    }

    public static void steerSetForward(Object packet, float forward) {
        if (!isSteerVehicle(packet))
            return;
        McAccess.setFloat(packet, "field_149617_b", forward);
    }

    public static void steerSetJump(Object packet, boolean jump) {
        if (!isSteerVehicle(packet))
            return;
        McAccess.setBool(packet, "field_149619_c", jump);
    }

    private static float readSteerFloat(Object packet, String fieldSrg, String getterSrg, String namedGetter) {
        Object fromGetter = McAccess.invoke(packet, getterSrg, new Class<?>[0]);
        if (fromGetter == null)
            fromGetter = McAccess.invokeNamed(packet, namedGetter, new Class<?>[0]);
        if (fromGetter instanceof Float)
            return (Float) fromGetter;
        return McAccess.getFloat(packet, fieldSrg);
    }

    private static boolean readSteerBoolean(Object packet, String fieldSrg, String getterSrg, String namedGetter) {
        Object fromGetter = McAccess.invoke(packet, getterSrg, new Class<?>[0]);
        if (fromGetter == null)
            fromGetter = McAccess.invokeNamed(packet, namedGetter, new Class<?>[0]);
        if (fromGetter instanceof Boolean)
            return (Boolean) fromGetter;
        return McAccess.getBool(packet, fieldSrg);
    }

    private static int readVelocityMotionComponent(Object packet, String fieldSrg, String getterSrg) {
        Object fromGetter = McAccess.invoke(packet, getterSrg, new Class<?>[0]);
        if (fromGetter instanceof Integer)
            return (Integer) fromGetter;
        return McAccess.getInt(packet, fieldSrg);
    }

    public static boolean isFallDamageVelocity(int motionX, int motionY, int motionZ) {
        return motionX == 0 && motionZ == 0 && motionY < 0;
    }

    public static boolean hasMeleeHorizontalKnockback(int motionX, int motionZ) {
        return Math.abs(motionX) >= MIN_MELEE_KB_HORIZONTAL_MOTION
                || Math.abs(motionZ) >= MIN_MELEE_KB_HORIZONTAL_MOTION;
    }

    /** Horizontal player-hit KB — excludes fall, vertical-only, and negligible motion (fire/poison). */
    public static boolean isMeleeKnockbackVelocity(int motionX, int motionY, int motionZ) {
        if (isFallDamageVelocity(motionX, motionY, motionZ))
            return false;
        return hasMeleeHorizontalKnockback(motionX, motionZ);
    }

    public static boolean isMeleeKnockbackVelocity(Object packet) {
        if (!isEntityVelocity(packet))
            return false;
        return isMeleeKnockbackVelocity(
                velocityMotionX(packet),
                velocityMotionY(packet),
                velocityMotionZ(packet));
    }

    /** Melee KB from a player hit — not fall, explosion, or environmental hurt without horizontal S12. */
    public static boolean isPlayerHitKnockbackVelocity(Object packet) {
        return isMeleeKnockbackVelocity(packet) && !isRecentExplosionKnockback();
    }

    public static boolean isUseEntity(Object packet) {
        return classNameContains(packet, "C02PacketUseEntity");
    }

    public static boolean isAttackUseEntity(Object packet) {
        if (!isUseEntity(packet))
            return false;
        Object action = McAccess.invoke(packet, "func_149565_c", new Class<?>[0]);
        if (action == null)
            action = McAccess.invokeNamed(packet, "getAction", new Class<?>[0]);
        return action != null && String.valueOf(action).contains("ATTACK");
    }

    public static boolean isExplosion(Object packet) {
        return classNameContains(packet, "S27PacketExplosion");
    }

    private static float readExplosionMotionComponent(Object packet, String srg, String mcp) {
        if (!isExplosion(packet))
            return 0f;
        try {
            float v = McAccess.getFloat(packet, srg);
            if (v != 0f)
                return v;
        } catch (Throwable ignored) {
        }
        Object boxed = McAccess.invoke(packet, mcp, new Class<?>[0]);
        if (boxed instanceof Float)
            return (Float) boxed;
        if (boxed instanceof Double)
            return ((Double) boxed).floatValue();
        return McAccess.getFloat(packet, srg);
    }

    private static void writeExplosionMotionComponent(Object packet, String srg, float value) {
        if (!isExplosion(packet))
            return;
        McAccess.setFloat(packet, srg, value);
    }

    public static float explosionMotionX(Object packet) {
        return readExplosionMotionComponent(packet, "field_149152_a", "func_149149_c");
    }

    public static float explosionMotionY(Object packet) {
        return readExplosionMotionComponent(packet, "field_149150_b", "func_149144_d");
    }

    public static float explosionMotionZ(Object packet) {
        return readExplosionMotionComponent(packet, "field_149151_c", "func_149147_e");
    }

    public static void explosionSetMotionX(Object packet, float motionX) {
        writeExplosionMotionComponent(packet, "field_149152_a", motionX);
    }

    public static void explosionSetMotionY(Object packet, float motionY) {
        writeExplosionMotionComponent(packet, "field_149150_b", motionY);
    }

    public static void explosionSetMotionZ(Object packet, float motionZ) {
        writeExplosionMotionComponent(packet, "field_149151_c", motionZ);
    }

    public static boolean isAnimationPacket(Object packet) {
        return classNameContains(packet, "C0APacketAnimation");
    }

    public static boolean isLookOrRotationPacket(Object packet) {
        if (packet == null)
            return false;
        String name = packet.getClass().getName();
        return name.contains("Look") || name.contains("Rotation");
    }

    public static boolean isClientConfirmTransaction(Object packet) {
        return classNameContains(packet, "C0FPacketConfirmTransaction");
    }

    public static boolean isServerConfirmTransaction(Object packet) {
        return classNameContains(packet, "S32PacketConfirmTransaction");
    }

    public static boolean isEntityAction(Object packet) {
        return classNameContains(packet, "C0BPacketEntityAction");
    }

    /** C0B START_SPRINTING / STOP_SPRINTING (Grim BadPacketsX sprint window). */
    public static boolean isSprintEntityAction(Object packet) {
        String action = entityActionName(packet);
        return action != null
            && (action.contains("START_SPRINTING") || action.contains("STOP_SPRINTING"));
    }

    public static boolean isStartSprintEntityAction(Object packet) {
        String action = entityActionName(packet);
        return action != null && action.contains("START_SPRINTING");
    }

    /** C0B START_SNEAKING / STOP_SNEAKING (Grim BadPacketsX sneak window). */
    public static boolean isSneakEntityAction(Object packet) {
        String action = entityActionName(packet);
        return action != null
            && (action.contains("START_SNEAKING") || action.contains("STOP_SNEAKING"));
    }

    private static String entityActionName(Object packet) {
        if (!isEntityAction(packet))
            return null;
        Object action = McAccess.invokeNamed(packet, "getAction", new Class<?>[0]);
        if (action == null)
            action = McAccess.invoke(packet, "func_180763_b", new Class<?>[0]);
        return action != null ? action.toString() : null;
    }

    public static boolean isPlayerDigging(Object packet) {
        return classNameContains(packet, "C07PacketPlayerDigging");
    }

    /** C07 block dig / drop — not RELEASE_USE_ITEM. */
    public static boolean isBlockDig(Object packet) {
        return isPlayerDigging(packet) && !isReleaseUseItem(packet);
    }

    public static boolean isHeldItemChange(Object packet) {
        return classNameContains(packet, "C09PacketHeldItemChange");
    }

    /** Hotbar index from {@code C09PacketHeldItemChange}, or {@code -1}. */
    public static int c09Slot(Object packet) {
        if (!isHeldItemChange(packet))
            return -1;
        Object value = McAccess.invokeNamed(packet, "getSlotId", new Class<?>[0]);
        if (value instanceof Integer)
            return (Integer) value;
        value = McAccess.invoke(packet, "func_149576_a", new Class<?>[0]);
        if (value instanceof Integer)
            return (Integer) value;
        int slot = McAccess.getInt(packet, "field_149576_a");
        if (slot >= 0 && slot <= 8)
            return slot;
        return -1;
    }

    /**
     * C07 RELEASE_USE_ITEM action — tells the server the player stopped blocking.
     * During autoblock lag windows, this MUST be cancelled to prevent the server
     * from seeing the block release (defeats autoblock). Checks both class name
     * and the action enum value.
     */
    public static boolean isReleaseUseItem(Object packet) {
        if (!isPlayerDigging(packet)) return false;
        Object action = McAccess.invoke(packet, "func_180762_a", new Class<?>[0]);
        if (action == null)
            action = McAccess.invokeNamed(packet, "getAction", new Class<?>[0]);
        return action != null && String.valueOf(action).contains("RELEASE_USE_ITEM");
    }

    public static boolean isBlockPlacement(Object packet) {
        return isInstanceOfGameClass(packet, "net.minecraft.network.play.client.C08PacketPlayerBlockPlacement")
                || classNameContains(packet, "C08PacketPlayerBlockPlacement");
    }

    /**
     * C08 use-item / block-place — matches any C08PacketPlayerBlockPlacement.
     * Used to drop C08 during autoblock lag windows (prevents MultiActions A from
     * buffered C08 bursts after C02 release). In 1.8.9, all C08s are the same class;
     * face=-1 distinguishes use-item from block-placement but both are caught here.
     */
    public static boolean isSendUseItem(Object packet) {
        return classNameContains(packet, "C08PacketPlayerBlockPlacement");
    }

    /** Held stack is ender pearl or a placeable block item (C08 use / block place). */
    public static boolean isHeldPearlOrPlaceableBlock(Object player) {
        if (player == null)
            return false;
        Object stack = McAccess.invoke(player, "func_70694_bm", new Class<?>[0]);
        if (stack == null)
            stack = McAccess.invokeNamed(player, "getHeldItem", new Class<?>[0]);
        if (stack == null)
            return false;
        Object item = McAccess.invoke(stack, "func_77973_b", new Class<?>[0]);
        if (item == null)
            item = McAccess.invokeNamed(stack, "getItem", new Class<?>[0]);
        if (item == null)
            return false;
        return isPearlOrPlaceableBlockItem(item.getClass().getName());
    }

    private static boolean isPearlOrPlaceableBlockItem(String itemClassName) {
        if (itemClassName == null)
            return false;
        if (itemClassName.contains("ItemEnderPearl"))
            return true;
        if (itemClassName.contains("ItemBlock"))
            return true;
        return false;
    }

    /** raven Stasis: exact {@code instanceof C03PacketPlayer.C05PacketPlayerLook}. */
    public static boolean isC05PacketPlayerLook(Object packet) {
        if (packet == null)
            return false;
        Class<?> c = packet.getClass();
        while (c != null) {
            if ("C05PacketPlayerLook".equals(c.getSimpleName()))
                return true;
            c = c.getSuperclass();
        }
        return false;
    }

    public static boolean isPlayerPosLook(Object packet) {
        return classNameContains(packet, "S08PacketPlayerPosLook");
    }

    public static double posLookX(Object packet) {
        return readPosLookDouble(packet, "field_148940_a", "func_148932_c");
    }

    public static double posLookY(Object packet) {
        return readPosLookDouble(packet, "field_148938_b", "func_148928_d");
    }

    public static double posLookZ(Object packet) {
        return readPosLookDouble(packet, "field_148939_c", "func_148933_e");
    }

    public static float posLookYaw(Object packet) {
        return readPosLookFloat(packet, "field_148936_d", "func_148931_f");
    }

    public static float posLookPitch(Object packet) {
        return readPosLookFloat(packet, "field_148937_e", "func_148929_g");
    }

    private static double readPosLookDouble(Object packet, String srg, String mcp) {
        if (!isPlayerPosLook(packet))
            return Double.NaN;
        double v = McAccess.getDouble(packet, srg);
        if (!Double.isNaN(v) && v != 0.0)
            return v;
        Object boxed = McAccess.invoke(packet, mcp, new Class<?>[0]);
        if (boxed instanceof Number)
            return ((Number) boxed).doubleValue();
        return McAccess.getDouble(packet, srg);
    }

    private static float readPosLookFloat(Object packet, String srg, String mcp) {
        if (!isPlayerPosLook(packet))
            return Float.NaN;
        float v = McAccess.getFloat(packet, srg);
        Object boxed = McAccess.invoke(packet, mcp, new Class<?>[0]);
        if (boxed instanceof Float)
            return (Float) boxed;
        if (boxed instanceof Double)
            return ((Double) boxed).floatValue();
        return v;
    }

    public static boolean isDisconnect(Object packet) {
        return classNameContains(packet, "S40PacketDisconnect")
                || classNameContains(packet, "S00PacketDisconnect");
    }

    public static boolean isUpdateHealth(Object packet) {
        return classNameContains(packet, "S06PacketUpdateHealth");
    }

    public static boolean isEntityStatus(Object packet) {
        return classNameContains(packet, "S19PacketEntityStatus");
    }

    /** S19 opCode 2 (hurt) for the local player — keep damage feedback immediate while lagging inbound. */
    public static boolean isSelfDamageEntityStatus(Object packet) {
        if (!isEntityStatus(packet))
            return false;
        Object op = McAccess.invoke(packet, "func_149161_b", new Class<?>[0]);
        if (op == null)
            op = McAccess.invokeNamed(packet, "getOpCode", new Class<?>[0]);
        if (!(op instanceof Byte) || (Byte) op != 2)
            return false;
        Object player = McAccess.thePlayer(McAccess.getMinecraft());
        if (player == null)
            return false;
        return entityId(packet) == McAccess.entityId(player);
    }

    /** Self {@code S12PacketEntityVelocity} — cancel outbound lag queue, do not burst stale C03. */
    public static boolean isSelfEntityVelocity(Object packet) {
        if (!isEntityVelocity(packet))
            return false;
        Object player = McAccess.thePlayer(McAccess.getMinecraft());
        return player != null && velocityEntityId(packet) == McAccess.entityId(player);
    }

    /**
     * Lagrange: only connection-critical packets bypass lag (raven {@code UnifiedLagHandler} queues
     * C0A/C0B/C0F/C03 together — splitting them caused Grim packet-order flags).
     */
    public static boolean isLagrangeSendExempt(Object packet) {
        return isKeepAlive(packet) || isChat(packet) || isClientConfirmTransaction(packet);
    }

    /** Block interact must flush movement queue first (Lagrange/Blink), not bypass it. */
    public static boolean isBlockInteract(Object packet) {
        return isPlayerDigging(packet) || isBlockPlacement(packet);
    }

    /** Blink: keepalive/chat/C0F/C0B/C0A exempt; Lagrange uses {@link #isLagrangeSendExempt} only. */
    public static boolean isOutboundQueueExempt(Object packet) {
        return isKeepAlive(packet)
                || isChat(packet)
                || isClientConfirmTransaction(packet)
                || isEntityAction(packet)
                || isAnimationPacket(packet);
    }

    /**
     * Blink must not split {@code C0A} from {@code C02} — Vulcan BadPackets {@code swung=false}.
     */
    public static boolean isBlinkOutboundExempt(Object packet) {
        return isOutboundQueueExempt(packet) || isUseEntity(packet);
    }

    /**
     * Inbound packets that must not be delayed (Backtrack, etc.).
     * Transaction confirms must reach the client immediately.
     */
    public static boolean isInboundQueueExempt(Object packet) {
        if (packet == null)
            return true;
        if (isServerConfirmTransaction(packet) || isClientConfirmTransaction(packet))
            return true;
        if (isKeepAlive(packet))
            return true;
        if (isChat(packet))
            return true;
        if (isPlayerPosLook(packet))
            return true;
        if (isDisconnect(packet))
            return true;
        return isUpdateHealth(packet);
    }

    public static boolean isKeepAlive(Object packet) {
        return classNameContains(packet, "C00PacketKeepAlive")
                || classNameContains(packet, "S00PacketKeepAlive");
    }

    public static boolean isClientSettings(Object packet) {
        return classNameContains(packet, "C15PacketClientSettings");
    }

    public static boolean isCustomPayload(Object packet) {
        return classNameContains(packet, "C17PacketCustomPayload");
    }

    public static boolean isChat(Object packet) {
        return classNameContains(packet, "C01PacketChatMessage")
                || classNameContains(packet, "S02PacketChatMessage");
    }

    /** Outbound {@code C01PacketChatMessage} only. */
    public static boolean isChatSend(Object packet) {
        return classNameContains(packet, "C01PacketChatMessage");
    }

    /**
     * Chat text from outbound {@code C01PacketChatMessage}.
     * stable_22: {@code field_149440_a} message, {@code func_149439_c} getMessage.
     */
    public static String chatMessage(Object packet) {
        if (!isChatSend(packet))
            return null;
        try {
            Object fromGetter = McAccess.invoke(packet, "func_149439_c", new Class<?>[0]);
            if (fromGetter instanceof String)
                return (String) fromGetter;
        } catch (Throwable ignored) {
        }
        try {
            Object fromField = McAccess.getObject(packet, "field_149440_a");
            if (fromField instanceof String)
                return (String) fromField;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean classNameContains(Object packet, String fragment) {
        return packet != null && packet.getClass().getName().contains(fragment);
    }

    private static boolean isInstanceOfGameClass(Object packet, String binaryName) {
        if (packet == null)
            return false;
        Class<?> cls = McAccess.gameClass(binaryName);
        return cls != null && cls.isInstance(packet);
    }
}
