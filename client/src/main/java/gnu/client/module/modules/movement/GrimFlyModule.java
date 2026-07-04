package gnu.client.module.modules.movement;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.mc.McAccess;
import gnu.client.runtime.packet.PacketEvents;
import gnu.client.runtime.packet.PacketHelper;
import gnu.client.runtime.packet.PacketListener;

/**
 * Grim 1.8.9 fly via boat vehicle exemption.
 *
 * <p>Patches {@code MovementInput} before vanilla builds the single {@code C0CPacketInput}
 * per tick (JVMTI hook). Packet onSend is a backup. Boats do not gain height in water —
 * launch off a ledge/waterfall first, then hold space + forward in air.
 */
public final class GrimFlyModule extends Module implements PacketListener {

    private final SliderSetting steer =
            addSetting(new SliderSetting("Steer", 0.98f, 0.1f, 0.98f));
    private final BoolSetting jumpOnSpace =
            addSetting(new BoolSetting("Jump on space", true));
    private final BoolSetting mouseYaw =
            addSetting(new BoolSetting("Mouse yaw", true));

    public GrimFlyModule() {
        super("Grim Fly", "Boat fly for Grim 1.8.9 — mount boat, launch into air", Category.PLAYER);
    }

    @Override
    public void onEnable() {
        PacketEvents.register(this);
    }

    @Override
    public void onDisable() {
        PacketEvents.unregister(this);
    }

    @Override
    public void onTickStart() {
        if (!isEnabled() || !mouseYaw.getValue())
            return;
        if (McAccess.thePlayer() == null)
            return;
        McAccess.setRotation(McAccess.getYaw(), McAccess.getPitch());
    }

    @Override
    public int sendPriority() {
        return 50;
    }

    @Override
    public boolean onSend(Object packet) {
        if (!isEnabled() || !McAccess.isRiding() || !PacketHelper.isSteerVehicle(packet))
            return false;
        applySteer(packet);
        return false;
    }

    @Override
    public boolean onReceive(Object packet) {
        return false;
    }

    /** Called from {@link gnu.client.runtime.MovementInputHook} after key state is read. */
    public static void patchMovementInput(Object movInput) {
        Module mod = ModuleManager.INSTANCE.getModule("Grim Fly");
        if (!(mod instanceof GrimFlyModule) || !mod.isEnabled())
            return;
        if (!McAccess.isRiding() || movInput == null)
            return;

        GrimFlyModule fly = (GrimFlyModule) mod;
        SteerInput input = fly.computeSteerInput();
        McAccess.setFloat(movInput, "field_78902_a", input.strafe);
        McAccess.setFloat(movInput, "field_78900_b", input.forward);
        McAccess.setBool(movInput, "field_78901_c", input.jump);
    }

    private void applySteer(Object packet) {
        SteerInput input = computeSteerInput();
        PacketHelper.steerSetStrafe(packet, input.strafe);
        PacketHelper.steerSetForward(packet, input.forward);
        PacketHelper.steerSetJump(packet, input.jump);
    }

    private SteerInput computeSteerInput() {
        float maxSteer = steer.getValue();
        if (maxSteer > 0.98f)
            maxSteer = 0.98f;

        float yaw = McAccess.getYaw();
        float forward = 0f;
        if (McAccess.isForwardKeyHeld()) forward += 1f;
        if (McAccess.isBackKeyHeld()) forward -= 1f;
        float strafe = 0f;
        if (McAccess.isLeftKeyHeld()) strafe += 1f;
        if (McAccess.isRightKeyHeld()) strafe -= 1f;

        if (forward != 0f) {
            if (strafe > 0f) yaw += forward > 0f ? -45f : 45f;
            else if (strafe < 0f) yaw += forward > 0f ? 45f : -45f;
            strafe = 0f;
            forward = forward > 0f ? 1f : -1f;
        }

        boolean jump = jumpOnSpace.getValue() && McAccess.isJumpKeyHeld();
        return new SteerInput(strafe * maxSteer, forward * maxSteer, jump);
    }

    private static final class SteerInput {
        final float strafe;
        final float forward;
        final boolean jump;

        SteerInput(float strafe, float forward, boolean jump) {
            this.strafe = strafe;
            this.forward = forward;
            this.jump = jump;
        }
    }
}
