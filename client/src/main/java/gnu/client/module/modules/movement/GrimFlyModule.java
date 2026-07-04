package gnu.client.module.modules.movement;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.ModeSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.mc.McAccess;
import gnu.client.runtime.packet.PacketEvents;
import gnu.client.runtime.packet.PacketHelper;
import gnu.client.runtime.packet.PacketListener;

import java.util.Arrays;

/**
 * Grim 1.8.9 fly — abuses real exemption paths, not client-motion cheating alone.
 *
 * <p><b>Vehicle</b> — on 1.8.9 {@code wasChecked=false} while mounted (Simulation skipped).
 * Works on boat, horse, pig, minecart — any ride. One steer/tick, cap 0.98.
 *
 * <p><b>Micro</b> — on-foot 0.03 position steps (Grim point-three window). Slow but real.
 *
 * <p><b>Knockback</b> — inflate inbound S12 Y/Z and ride the lenience window.
 */
public final class GrimFlyModule extends Module implements PacketListener {

    private static final String[] MODES = { "Vehicle", "Micro", "Knockback" };
    private static final float MAX_STEER = 0.98f;
    private static final float MAX_MICRO_STEP = 0.029f;

    private final ModeSetting mode =
            addSetting(new ModeSetting("Mode", 0, Arrays.asList(MODES)));
    private final BoolSetting autoMode =
            addSetting(new BoolSetting("Auto vehicle", true));
    private final SliderSetting steer =
            addSetting(new SliderSetting("Steer", 0.98f, 0.1f, 0.98f));
    private final SliderSetting microStep =
            addSetting(new SliderSetting("Micro step", 0.029f, 0.01f, 0.03f));
    private final SliderSetting kbMult =
            addSetting(new SliderSetting("KB mult", 4.0f, 1.0f, 8.0f));
    private final BoolSetting jumpOnSpace =
            addSetting(new BoolSetting("Jump on space", true));
    private final BoolSetting mouseYaw =
            addSetting(new BoolSetting("Mouse yaw", true));

    private double microX;
    private double microY;
    private double microZ;
    private boolean microReady;

    public GrimFlyModule() {
        super("Grim Fly", "Grim 1.8.9 fly — vehicle exempt / micro-step / KB", Category.PLAYER);
    }

    @Override
    public void onEnable() {
        PacketEvents.register(this);
        microReady = false;
    }

    @Override
    public void onDisable() {
        PacketEvents.unregister(this);
        microReady = false;
    }

    @Override
    public void onTickStart() {
        if (!isEnabled())
            return;
        Object player = McAccess.thePlayer();
        if (player == null)
            return;

        if (mouseYaw.getValue())
            McAccess.setRotation(McAccess.getYaw(), McAccess.getPitch());

        if (activeMode() == 0 && McAccess.isRiding() && jumpOnSpace.getValue() && McAccess.isJumpKeyHeld())
            McAccess.setJumpInput(player, true);

        if (activeMode() == 1 && !McAccess.isRiding())
            tickMicro(player);
    }

    @Override
    public int sendPriority() {
        return 50;
    }

    @Override
    public boolean onSend(Object packet) {
        if (!isEnabled())
            return false;

        if (activeMode() == 0 && McAccess.isRiding() && PacketHelper.isSteerVehicle(packet)) {
            applySteer(packet);
            return false;
        }

        if (activeMode() == 1 && !McAccess.isRiding()
                && PacketHelper.isPlayerMovement(packet) && PacketHelper.c03HasPosition(packet)) {
            if (!microReady)
                return false;
            PacketHelper.c03SetPosition(packet, microX, microY, microZ);
            PacketHelper.c03SetOnGround(packet, false);
        }
        return false;
    }

    @Override
    public boolean onReceive(Object packet) {
        if (!isEnabled() || activeMode() != 2)
            return false;
        if (!PacketHelper.isSelfEntityVelocity(packet))
            return false;

        int mx = PacketHelper.velocityMotionX(packet);
        int my = PacketHelper.velocityMotionY(packet);
        int mz = PacketHelper.velocityMotionZ(packet);
        if (mx == 0 && my == 0 && mz == 0)
            return false;

        float mult = kbMult.getValue();
        if (mx != 0)
            PacketHelper.velocitySetMotionX(packet, (int) (mx * mult));
        if (mz != 0)
            PacketHelper.velocitySetMotionZ(packet, (int) (mz * mult));
        if (my != 0)
            PacketHelper.velocitySetMotionY(packet, (int) (my * mult));
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
        if (fly.activeMode() != 0)
            return;

        SteerInput input = fly.computeSteerInput();
        McAccess.setFloat(movInput, "field_78902_a", input.strafe);
        McAccess.setFloat(movInput, "field_78900_b", input.forward);
        McAccess.setBool(movInput, "field_78901_c", input.jump);
    }

    private int activeMode() {
        if (autoMode.getValue() && McAccess.isRiding())
            return 0;
        return mode.getValue();
    }

    private void tickMicro(Object player) {
        float step = microStep.getValue();
        if (step > MAX_MICRO_STEP)
            step = MAX_MICRO_STEP;

        if (!microReady) {
            microX = McAccess.entityPosX(player);
            microY = McAccess.entityPosY(player);
            microZ = McAccess.entityPosZ(player);
            microReady = true;
        }

        double nx = microX;
        double ny = microY;
        double nz = microZ;

        if (jumpOnSpace.getValue() && McAccess.isJumpKeyHeld())
            ny += step;

        float forward = 0f;
        if (McAccess.isForwardKeyHeld()) forward += 1f;
        if (McAccess.isBackKeyHeld()) forward -= 1f;
        float strafe = 0f;
        if (McAccess.isLeftKeyHeld()) strafe += 1f;
        if (McAccess.isRightKeyHeld()) strafe -= 1f;

        if (forward != 0f || strafe != 0f) {
            double yaw = Math.toRadians(McAccess.getYaw());
            double sin = Math.sin(yaw);
            double cos = Math.cos(yaw);
            nx += (-sin * forward + cos * strafe) * step;
            nz += (cos * forward + sin * strafe) * step;
        }

        microX = nx;
        microY = ny;
        microZ = nz;
        McAccess.setEntityPosition(player, nx, ny, nz);
        McAccess.setMotion(0, 0, 0);
    }

    private void applySteer(Object packet) {
        SteerInput input = computeSteerInput();
        PacketHelper.steerSetStrafe(packet, input.strafe);
        PacketHelper.steerSetForward(packet, input.forward);
        PacketHelper.steerSetJump(packet, input.jump);
    }

    private SteerInput computeSteerInput() {
        float maxSteer = steer.getValue();
        if (maxSteer > MAX_STEER)
            maxSteer = MAX_STEER;

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
