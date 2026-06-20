package gnu.client.module.modules.network;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.mc.McAccess;
import gnu.client.runtime.packet.OutboundLagQueue;
import gnu.client.runtime.packet.PacketEvents;
import gnu.client.runtime.packet.PacketHelper;
import gnu.client.runtime.packet.PacketListener;
import gnu.client.runtime.packet.PacketUtil;
import gnu.client.util.RenderHelper;

/**
 * raven-bS {@code Blink} — hold outbound packets until disable.
 * Optional step release drains one queued packet every {@link #STEP_RELEASE_INTERVAL_TICKS} ticks.
 */
public final class BlinkModule extends Module implements PacketListener {

    private static final int STEP_RELEASE_INTERVAL_TICKS = 4;

    private final OutboundLagQueue outbound = new OutboundLagQueue();
    private final BoolSetting stepRelease = addSetting(new BoolSetting("Step release", false));
    private final BoolSetting serverEsp = addSetting(new BoolSetting("Server ESP", true));
    private final SliderSetting espRed = addSetting(new SliderSetting("Red", 0.0f, 0.0f, 255.0f));
    private final SliderSetting espGreen = addSetting(new SliderSetting("Green", 255.0f, 0.0f, 255.0f));
    private final SliderSetting espBlue = addSetting(new SliderSetting("Blue", 0.0f, 0.0f, 255.0f));
    private final BoolSetting espFilled = addSetting(new BoolSetting("Filled", false));
    private final SliderSetting espLineWidth = addSetting(new SliderSetting("Line Width", 1.0f, 1.0f, 3.0f));
    private boolean frozenPosValid;
    private double frozenX;
    private double frozenY;
    private double frozenZ;
    private int stepReleaseTickCounter;

    public BlinkModule() {
        super("Blink", "Hold outbound packets (Raven); optional step release", Category.MISC);
    }

    @Override
    public void onEnable() {
        Module lag = ModuleManager.INSTANCE.getModule("Lagrange");
        if (lag instanceof LagrangeModule && lag.isEnabled())
            ((LagrangeModule) lag).pauseForBlink();
        outbound.clear();
        stepReleaseTickCounter = 0;
        freezeCurrentPosition();
        PacketEvents.register(this);
    }

    @Override
    public void onDisable() {
        PacketEvents.unregister(this);
        drainOutboundQueue();
    }

    @Override
    public void onTick() {
        if (!isEnabled() || !stepRelease.getValue())
            return;
        stepReleaseTickCounter++;
        if (stepReleaseTickCounter >= STEP_RELEASE_INTERVAL_TICKS) {
            stepReleaseTickCounter = 0;
            releaseOneHeldPacket();
        }
    }

    @Override
    public boolean onSend(Object packet) {
        if (PacketUtil.isDispatching())
            return false;
        if (PacketHelper.isBlockInteract(packet)) {
            drainOutboundQueue();
            return false;
        }
        if (PacketHelper.isBlinkOutboundExempt(packet))
            return false;
        outbound.offer(packet);
        return true;
    }

    @Override
    public boolean onReceive(Object packet) {
        return false;
    }

    @Override
    public void onRender(float partialTicks) {
        if (!serverEsp.getValue() || outbound.isEmpty() || !McAccess.isInGame() || !frozenPosValid)
            return;
        Object mc = McAccess.getMinecraft();
        double[] vp = McAccess.getViewerPos(mc, partialTicks);
        float fr = espRed.getValue() / 255.0f;
        float fg = espGreen.getValue() / 255.0f;
        float fb = espBlue.getValue() / 255.0f;
        float lw = espLineWidth.getValue();

        RenderHelper.begin();
        drawGhostBox(frozenX - vp[0], frozenY - vp[1], frozenZ - vp[2], fr, fg, fb, lw);
        RenderHelper.end();
    }

    private void freezeCurrentPosition() {
        Object mc = McAccess.getMinecraft();
        Object player = McAccess.thePlayer(mc);
        if (player == null) {
            frozenPosValid = false;
            return;
        }
        frozenX = McAccess.entityPosX(player);
        frozenY = McAccess.entityPosY(player);
        frozenZ = McAccess.entityPosZ(player);
        frozenPosValid = true;
    }

    private void releaseOneHeldPacket() {
        outbound.drainUpTo(1, this::releaseHeldPacket);
    }

    private void releaseHeldPacket(Object pkt) {
        if (pkt == null)
            return;
        if (PacketHelper.isAttackUseEntity(pkt))
            PacketUtil.sendSwingAnimation();
        PacketUtil.sendPacketReleased(pkt);
    }

    private void drainOutboundQueue() {
        outbound.drainAll(this::releaseHeldPacket);
    }

    private void drawGhostBox(double rx, double ry, double rz,
                              float r, float g, float b, float lineWidth) {
        if (espFilled.getValue()) {
            RenderHelper.drawFilledBox(
                    rx - 0.3, ry, rz - 0.3,
                    rx + 0.3, ry + 1.8, rz + 0.3,
                    r, g, b, 0.25f);
        }
        RenderHelper.drawBoundingBox(
                rx - 0.3, ry, rz - 0.3,
                rx + 0.3, ry + 1.8, rz + 0.3,
                r, g, b, 1.0f, lineWidth);
    }
}
