package gnu.client.script;

import gnu.client.runtime.mc.McAccess;

/** Script-facing player/entity status facade over {@link McAccess}. */
public final class Status {

    public static final Status INSTANCE = new Status();

    private Status() {}

    public boolean isUsingItem() {
        return McAccess.isUsingItem();
    }

    public boolean isUsingItem(Object player) {
        return McAccess.isUsingItem(player);
    }

    public boolean isBlocking() {
        return McAccess.isBlocking();
    }

    public boolean isBlocking(Object player) {
        return McAccess.isBlocking(player);
    }

    public int getHurtTime() {
        return McAccess.getHurtTime();
    }

    public int getHurtTime(Object entity) {
        return McAccess.getHurtTime(entity);
    }

    public int getMaxHurtTime(Object entity) {
        return McAccess.getMaxHurtTime(entity);
    }

    public int getDeathTime(Object entity) {
        return McAccess.getDeathTime(entity);
    }

    public float getHealth() {
        return McAccess.getHealth();
    }

    public float getHealth(Object entity) {
        return McAccess.getHealth(entity);
    }

    public float getMaxHealth() {
        return McAccess.getMaxHealth();
    }

    public float getMaxHealth(Object entity) {
        return McAccess.getMaxHealth(entity);
    }

    public float getAbsorption() {
        return McAccess.getAbsorption();
    }

    public float getAbsorption(Object entity) {
        return McAccess.getAbsorption(entity);
    }

    public boolean isDead() {
        return McAccess.isDead();
    }

    public boolean isDead(Object entity) {
        return McAccess.isDead(entity);
    }

    public boolean isAlive() {
        return McAccess.isAlive();
    }

    public boolean isAlive(Object entity) {
        return McAccess.isAlive(entity);
    }

    public boolean isSwingInProgress() {
        return McAccess.isSwingInProgress();
    }

    public boolean isSwingInProgress(Object player) {
        return McAccess.isSwingInProgress(player);
    }

    public Object getHeldItemStack() {
        return McAccess.getHeldItemStack();
    }

    public Object getHeldItemStack(Object player) {
        return McAccess.getHeldItemStack(player);
    }

    public boolean isHoldingSword() {
        return McAccess.isHoldingSword();
    }

    public boolean isHoldingBlock() {
        return McAccess.isHoldingBlock();
    }

    public boolean isHoldingBow() {
        return McAccess.isHoldingBow();
    }

    public boolean isHoldingConsumable() {
        return McAccess.isHoldingConsumable();
    }

    public boolean isInWater() {
        return McAccess.isInWater();
    }

    public boolean isInWater(Object entity) {
        return McAccess.isInWater(entity);
    }
}
