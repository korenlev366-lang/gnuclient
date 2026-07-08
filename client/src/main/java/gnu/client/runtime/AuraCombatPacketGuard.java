package gnu.client.runtime;

import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.modules.combat.KillAuraModule;
import gnu.client.runtime.packet.PacketHelper;
import gnu.client.runtime.packet.PacketListener;

/**
 * Grim BadPacketsX parity: at most one sprint and one sneak C0B between C03 packets.
 * Also blocks START_SPRINTING on the aura attack tick (vanilla attack slow window).
 *
 * <p>Registered while KillAura is enabled. {@link gnu.client.runtime.mc.McAccess#sendSprintActionPacket}
 * consults {@link #shouldCancelEntityAction} so injected C0B cannot bypass this guard.
 */
public final class AuraCombatPacketGuard implements PacketListener {

    private static final AuraCombatPacketGuard INSTANCE = new AuraCombatPacketGuard();

    private boolean sprintActionSinceMove;
    private boolean sneakActionSinceMove;

    private AuraCombatPacketGuard() {}

    public static void register() {
        gnu.client.runtime.packet.PacketEvents.register(INSTANCE);
    }

    public static void unregister() {
        gnu.client.runtime.packet.PacketEvents.unregister(INSTANCE);
        INSTANCE.sprintActionSinceMove = false;
        INSTANCE.sneakActionSinceMove = false;
    }

    /** Called from {@code McAccess.sendSprintActionPacket} before queueing. */
    public static boolean shouldCancelEntityAction(Object packet) {
        if (!isGuardActive())
            return false;
        return INSTANCE.checkEntityAction(packet);
    }

    @Override
    public int sendPriority() {
        return 900;
    }

    @Override
    public boolean onSend(Object packet) {
        if (!isGuardActive())
            return false;
        if (PacketHelper.isPlayerMovement(packet)) {
            sprintActionSinceMove = false;
            sneakActionSinceMove = false;
            return false;
        }
        return checkEntityAction(packet);
    }

    @Override
    public boolean onReceive(Object packet) {
        return false;
    }

    private boolean checkEntityAction(Object packet) {
        if (PacketHelper.isSprintEntityAction(packet))
            return checkSprintAction(packet);
        if (PacketHelper.isSneakEntityAction(packet))
            return checkSneakAction();
        return false;
    }

    private boolean checkSprintAction(Object packet) {
        if (KillAuraModule.shouldSuppressSprintRestart()
            && PacketHelper.isStartSprintEntityAction(packet))
            return true;
        if (sprintActionSinceMove)
            return true;
        sprintActionSinceMove = true;
        return false;
    }

    private boolean checkSneakAction() {
        if (sneakActionSinceMove)
            return true;
        sneakActionSinceMove = true;
        return false;
    }

    private static boolean isGuardActive() {
        Module module = ModuleManager.instance().getModule("KillAura");
        return module instanceof KillAuraModule && module.isEnabled();
    }
}
