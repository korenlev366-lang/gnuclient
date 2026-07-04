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

    /** Boat/horse input packet — keep forward/sideways under 0.98 for Grim VehicleA. */
    public void sendSteer(float strafe, float forward, boolean jump, boolean unmount) {
        McAccess.sendSteerVehicle(strafe, forward, jump, unmount);
    }

    /** {@code C07 RELEASE_USE_ITEM} — clears Grim/server item-use slow (noslow Grim mode). */
    public void releaseUseItem() {
        McAccess.sendReleaseUseItem(getPlayer());
    }

    /** Raven-style {@code C09} slot flick (noslow Grim mode). */
    public void heldItemChangeFlicker() {
        McAccess.sendHeldItemChangeFlicker();
    }

    public void setSprintKey(boolean pressed) {
        McAccess.setSprintKeyState(pressed);
    }

    /** Teleport local player to world coords (micro-step / blink fly). */
    public void setPlayerPosition(double x, double y, double z) {
        McAccess.setEntityPosition(getPlayer(), x, y, z);
    }
}
