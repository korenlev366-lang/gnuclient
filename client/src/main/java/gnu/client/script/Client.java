package gnu.client.script;

import gnu.client.runtime.mc.McAccess;

/**
 * Script-facing {@code client} accessor — a stateless singleton facade over
 * {@link McAccess}. Exposes the local player, wall-clock time, and a
 * player-eye raycast to script bodies without any {@code net.minecraft.*}
 * compile-time reference.
 *
 * <p>This class holds NO game-object state across calls (no cached player/world
 * fields). Every method re-resolves through {@code McAccess} so the script
 * class remains unloadable when its module is disabled — see the leak-risk
 * constraint in the scripting feasibility report.
 */
public final class Client {

    public static final Client INSTANCE = new Client();

    private Client() {}

    /** The local {@code EntityPlayerSP} as a raw Object, or {@code null} if not in-game. */
    public Object getPlayer() {
        return McAccess.thePlayer();
    }

    /** Wall-clock millis ({@code System.currentTimeMillis()}). */
    public long time() {
        return System.currentTimeMillis();
    }

    /**
     * Ray-cast from the local player's eyes along the given yaw/pitch, returning
     * the {@code MovingObjectPosition} (or {@code null} on miss / no world).
     *
     * @param distance reach distance in blocks
     * @param yaw      player yaw in degrees
     * @param pitch    player pitch in degrees
     */
    public Object raycastBlock(double distance, float yaw, float pitch) {
        return McAccess.raycastBlocks(distance, yaw, pitch);
    }

    public double getMotionX() {
        return McAccess.getMotionX();
    }

    public double getMotionY() {
        return McAccess.getMotionY();
    }

    public double getMotionZ() {
        return McAccess.getMotionZ();
    }

    public float getYaw() {
        return McAccess.getYaw();
    }

    public float getPitch() {
        return McAccess.getPitch();
    }

    public boolean isOnGround() {
        return McAccess.isOnGround();
    }

    public boolean isSneaking() {
        return McAccess.isSneaking();
    }

    public boolean isSprinting() {
        return McAccess.isClientSprinting();
    }

    public float getTimerSpeed() {
        return McAccess.getTimerSpeed();
    }

    public void setTimerSpeed(float speed) {
        McAccess.setTimerSpeed(speed);
    }

    public void resetTimer() {
        McAccess.resetTimer();
    }

    public void setRotation(float yaw, float pitch) {
        McAccess.setRotation(yaw, pitch);
    }

    public void setMotion(double x, double y, double z) {
        McAccess.setMotion(x, y, z);
    }

    public double getPosX() {
        Object player = getPlayer();
        return player == null ? 0.0 : McAccess.entityPosX(player);
    }

    public double getPosY() {
        Object player = getPlayer();
        return player == null ? 0.0 : McAccess.entityPosY(player);
    }

    public double getPosZ() {
        Object player = getPlayer();
        return player == null ? 0.0 : McAccess.entityPosZ(player);
    }

    /** Drive vanilla jump input (MovementInput.jump + keyBindJump state). */
    public void setJump(boolean jump) {
        McAccess.setJumpInput(getPlayer(), jump);
    }

    public boolean isRiding() {
        return McAccess.isRiding();
    }

    /** Riding entity ({@code Entity}) or null. */
    public Object getRidingEntity() {
        return McAccess.getRidingEntity(getPlayer());
    }

    public void setRidingMotion(double x, double y, double z) {
        McAccess.setEntityMotion(getRidingEntity(), x, y, z);
    }

    public double entityPosX(Object entity) {
        return entity == null ? 0.0 : McAccess.entityPosX(entity);
    }

    public double entityPosY(Object entity) {
        return entity == null ? 0.0 : McAccess.entityPosY(entity);
    }

    public double entityPosZ(Object entity) {
        return entity == null ? 0.0 : McAccess.entityPosZ(entity);
    }

    public void setEntityPosition(Object entity, double x, double y, double z) {
        McAccess.setEntityPosition(entity, x, y, z);
    }

    public void setEntityVelocity(Object entity, double x, double y, double z) {
        McAccess.setEntityVelocity(entity, x, y, z);
    }

    public void setEntityYaw(Object entity, float yaw) {
        McAccess.setEntityYaw(entity, yaw);
    }

    /** {@code C0C} steer packet for mounted vehicles (boat/horse/pig/minecart). */
    public void sendSteer(float strafe, float forward, boolean jump, boolean unmount) {
        McAccess.sendSteerVehicle(strafe, forward, jump, unmount);
    }

    /** {@code C07 RELEASE_USE_ITEM} — clears server item-use slow while eating/blocking. */
    public void releaseUseItem() {
        McAccess.sendReleaseUseItem(getPlayer());
    }

    /** {@code C09} hotbar slot flick (brief slot swap to reset use state). */
    public void heldItemChangeFlicker() {
        McAccess.sendHeldItemChangeFlicker();
    }

    public void setSprintKey(boolean pressed) {
        McAccess.setSprintKeyState(pressed);
    }

    public void setForwardKey(boolean pressed) {
        McAccess.setForwardKeyState(pressed);
    }

    public void setBackKey(boolean pressed) {
        McAccess.setBackKeyState(pressed);
    }

    public void setLeftKey(boolean pressed) {
        McAccess.setLeftKeyState(pressed);
    }

    public void setRightKey(boolean pressed) {
        McAccess.setRightKeyState(pressed);
    }

    /** Override {@code MovementInput} after vanilla key read (script {@code patchMovementInput} hook). */
    public void setMovementInput(Object movInput, float moveForward, float moveStrafe, boolean jump) {
        McAccess.setMovementInput(movInput, moveForward, moveStrafe, jump);
    }

    /** {@code EntityLivingBase.setSprinting} — pairs with forward movement for bhop speed. */
    public void setSprinting(boolean sprinting) {
        McAccess.setClientSprinting(getPlayer(), sprinting);
    }

    /** Force local onGround after setback snap (pairs with C03 ground sync). */
    public void setOnGround(boolean onGround) {
        McAccess.setOnGround(getPlayer(), onGround);
    }

    /** Teleport local player to world coords (micro-step / blink fly). */
    public void setPlayerPosition(double x, double y, double z) {
        McAccess.setEntityPosition(getPlayer(), x, y, z);
    }

    /** Packet sprint state the server last ack'd ({@code serverSprintState}). */
    public boolean isServerSprinting() {
        return McAccess.getServerSprintState();
    }

    /** {@code C0B START_SPRINTING} when server thinks we are not sprinting. */
    public void sendSprintStart() {
        McAccess.sendSprintActionPacket(getPlayer(), true);
    }

    /** {@code C0B STOP_SPRINTING} when server thinks we are sprinting. */
    public void sendSprintStop() {
        McAccess.sendSprintActionPacket(getPlayer(), false);
    }

    /** {@code PlayerControllerMP.attackEntity} — real C02 to server. */
    public boolean attackEntity(Object entity) {
        return McAccess.attackEntity(entity);
    }
}
