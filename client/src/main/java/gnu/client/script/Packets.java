package gnu.client.script;

import gnu.client.runtime.mc.McAccess;
import gnu.client.runtime.packet.PacketHelper;
import gnu.client.runtime.packet.PacketUtil;

/**
 * Script-facing packet helper facade. Methods delegate to the packet helpers
 * used by native modules; no script code needs raw packet reflection.
 */
public final class Packets {

    public static final Packets INSTANCE = new Packets();

    private static final String FIELD_C03_YAW = "field_149476_e";
    private static final String FIELD_C03_PITCH = "field_149473_f";

    private Packets() {}

    public boolean isAttack(Object packet) {
        return PacketHelper.isAttackUseEntity(packet);
    }

    public boolean isUseEntity(Object packet) {
        return PacketHelper.isUseEntity(packet);
    }

    public boolean isMovement(Object packet) {
        return PacketHelper.isPlayerMovement(packet);
    }

    public boolean hasPosition(Object packet) {
        return PacketHelper.c03HasPosition(packet);
    }

    public boolean isAnimation(Object packet) {
        return PacketHelper.isAnimationPacket(packet);
    }

    public boolean isBlockPlacement(Object packet) {
        return PacketHelper.isBlockPlacement(packet);
    }

    public boolean isReleaseUseItem(Object packet) {
        return PacketHelper.isReleaseUseItem(packet);
    }

    public boolean isKeepAlive(Object packet) {
        return PacketHelper.isKeepAlive(packet);
    }

    public boolean isTransaction(Object packet) {
        return PacketHelper.isClientConfirmTransaction(packet)
                || PacketHelper.isServerConfirmTransaction(packet);
    }

    public boolean isClientTransaction(Object packet) {
        return PacketHelper.isClientConfirmTransaction(packet);
    }

    public boolean isServerTransaction(Object packet) {
        return PacketHelper.isServerConfirmTransaction(packet);
    }

    public boolean isEntityAction(Object packet) {
        return PacketHelper.isEntityAction(packet);
    }

    public boolean isBlockDig(Object packet) {
        return PacketHelper.isBlockDig(packet);
    }

    public boolean isHeldItemChange(Object packet) {
        return PacketHelper.isHeldItemChange(packet);
    }

    public boolean isSendUseItem(Object packet) {
        return PacketHelper.isSendUseItem(packet);
    }

    public boolean isChatSend(Object packet) {
        return PacketHelper.isChatSend(packet);
    }

    public boolean isChat(Object packet) {
        return PacketHelper.isChat(packet);
    }

    public boolean isClientSettings(Object packet) {
        return PacketHelper.isClientSettings(packet);
    }

    public boolean isCustomPayload(Object packet) {
        return PacketHelper.isCustomPayload(packet);
    }

    public boolean isUpdateHealth(Object packet) {
        return PacketHelper.isUpdateHealth(packet);
    }

    public boolean isVelocity(Object packet) {
        return PacketHelper.isEntityVelocity(packet);
    }

    public boolean isSelfVelocity(Object packet) {
        return PacketHelper.isSelfEntityVelocity(packet);
    }

    public boolean isExplosion(Object packet) {
        return PacketHelper.isExplosion(packet);
    }

    public boolean isPlayerPosLook(Object packet) {
        return PacketHelper.isPlayerPosLook(packet);
    }

    public double posLookX(Object packet) {
        return PacketHelper.posLookX(packet);
    }

    public double posLookY(Object packet) {
        return PacketHelper.posLookY(packet);
    }

    public double posLookZ(Object packet) {
        return PacketHelper.posLookZ(packet);
    }

    public float posLookYaw(Object packet) {
        return PacketHelper.posLookYaw(packet);
    }

    public float posLookPitch(Object packet) {
        return PacketHelper.posLookPitch(packet);
    }

    public String simpleName(Object packet) {
        return packet == null ? "" : packet.getClass().getSimpleName();
    }

    public int entityId(Object packet) {
        int id = PacketHelper.entityId(packet);
        return id >= 0 ? id : PacketHelper.packetEntityId(packet);
    }

    public double movementX(Object packet) {
        return PacketHelper.c03PosX(packet);
    }

    public double movementY(Object packet) {
        return PacketHelper.c03PosY(packet);
    }

    public double movementZ(Object packet) {
        return PacketHelper.c03PosZ(packet);
    }

    public boolean movementOnGround(Object packet) {
        return PacketHelper.c03OnGround(packet);
    }

    public int velocityMotionX(Object packet) {
        return PacketHelper.velocityMotionX(packet);
    }

    public int velocityMotionY(Object packet) {
        return PacketHelper.velocityMotionY(packet);
    }

    public int velocityMotionZ(Object packet) {
        return PacketHelper.velocityMotionZ(packet);
    }

    public void setMovementRotation(Object packet, float yaw, float pitch) {
        if (!PacketHelper.isPlayerMovement(packet))
            return;
        McAccess.setFloat(packet, FIELD_C03_YAW, yaw);
        McAccess.setFloat(packet, FIELD_C03_PITCH, pitch);
    }

    public void setMovementPosition(Object packet, double x, double y, double z) {
        PacketHelper.c03SetPosition(packet, x, y, z);
    }

    public void setMovementOnGround(Object packet, boolean onGround) {
        PacketHelper.c03SetOnGround(packet, onGround);
    }

    public void setVelocityMotionX(Object packet, int motionX) {
        PacketHelper.velocitySetMotionX(packet, motionX);
    }

    public void setVelocityMotionY(Object packet, int motionY) {
        PacketHelper.velocitySetMotionY(packet, motionY);
    }

    public void setVelocityMotionZ(Object packet, int motionZ) {
        PacketHelper.velocitySetMotionZ(packet, motionZ);
    }

    public float explosionMotionX(Object packet) {
        return PacketHelper.explosionMotionX(packet);
    }

    public float explosionMotionY(Object packet) {
        return PacketHelper.explosionMotionY(packet);
    }

    public float explosionMotionZ(Object packet) {
        return PacketHelper.explosionMotionZ(packet);
    }

    public void setExplosionMotionX(Object packet, float motionX) {
        PacketHelper.explosionSetMotionX(packet, motionX);
    }

    public void setExplosionMotionY(Object packet, float motionY) {
        PacketHelper.explosionSetMotionY(packet, motionY);
    }

    public void setExplosionMotionZ(Object packet, float motionZ) {
        PacketHelper.explosionSetMotionZ(packet, motionZ);
    }

    public boolean isSteerVehicle(Object packet) {
        return PacketHelper.isSteerVehicle(packet);
    }

    public float steerStrafe(Object packet) {
        return PacketHelper.steerStrafe(packet);
    }

    public float steerForward(Object packet) {
        return PacketHelper.steerForward(packet);
    }

    public boolean steerJump(Object packet) {
        return PacketHelper.steerJump(packet);
    }

    public void setSteerStrafe(Object packet, float strafe) {
        PacketHelper.steerSetStrafe(packet, strafe);
    }

    public void setSteerForward(Object packet, float forward) {
        PacketHelper.steerSetForward(packet, forward);
    }

    public void setSteerJump(Object packet, boolean jump) {
        PacketHelper.steerSetJump(packet, jump);
    }

    public void sendReleased(Object packet) {
        PacketUtil.sendPacketReleased(packet);
    }

    public void processInbound(Object packet) {
        PacketUtil.processInbound(packet);
    }
}
