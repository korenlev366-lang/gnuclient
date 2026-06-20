package gnu.client.module.modules.movement;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.runtime.mc.McAccess;
import gnu.client.runtime.packet.PacketEvents;
import gnu.client.runtime.packet.PacketHelper;
import gnu.client.runtime.packet.PacketListener;

/**
 * raven-bS {@code Stasis} — zero local motion/input and block position {@code C03} packets
 * (look-only {@code C05PacketPlayerLook} still sent).
 */
public final class StasisModule extends Module implements PacketListener {

    public StasisModule() {
        super("Stasis", "Freeze movement", Category.PLAYER);
    }

    @Override
    public void onEnable() {
        PacketEvents.register(this);
    }

    @Override
    public void onDisable() {
        PacketEvents.unregister(this);
    }

    /** raven {@code PreUpdateEvent} — {@code EntityPlayerSP.onUpdate} HEAD via {@code PlayerUpdateHook}. */
    public static void onPreUpdate(Object player) {
        Module module = gnu.client.module.ModuleManager.INSTANCE.getModule("Stasis");
        if (!(module instanceof StasisModule) || !module.isEnabled())
            return;
        if (player == null || McAccess.thePlayer() == null)
            return;

        McAccess.setDouble(player, "field_70159_w", 0.0);
        McAccess.setDouble(player, "field_70181_x", 0.0);
        McAccess.setDouble(player, "field_70179_y", 0.0);
    }

    /** raven {@code PrePlayerInputEvent} — {@code MovementInputHook.afterUpdatePlayerMoveState}. */
    public static void patchPlayerInput(Object movementInput) {
        Module module = gnu.client.module.ModuleManager.INSTANCE.getModule("Stasis");
        if (!(module instanceof StasisModule) || !module.isEnabled())
            return;
        if (movementInput == null)
            return;

        McAccess.setFloat(movementInput, "field_78902_a", 0.0f);
        McAccess.setFloat(movementInput, "field_78900_b", 0.0f);
    }

    @Override
    public boolean onSend(Object packet) {
        if (!isEnabled())
            return false;
        if (!PacketHelper.isPlayerMovement(packet))
            return false;
        return !PacketHelper.isC05PacketPlayerLook(packet);
    }

    @Override
    public boolean onReceive(Object packet) {
        return false;
    }
}
