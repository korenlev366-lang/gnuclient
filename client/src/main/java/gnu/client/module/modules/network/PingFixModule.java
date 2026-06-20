package gnu.client.module.modules.network;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.mc.McAccess;

/**
 * Timewarp PingFix — hides the inflated ping caused by lag modules
 * (Backtrack, Lagrange, KnockbackDelay, Blink) by fixing the displayed
 * response time in the tab list.
 *
 * <p>When lag modules are active, the server sees delayed packets and
 * reports a higher ping. PingFix overwrites {@code NetworkPlayerInfo.field_178856_c}
 * (responseTime) with a configurable value each tick.
 *
 * <p>In "Freeze on lag" mode, the module captures the clean ping before
 * lag starts and freezes it, rather than always showing a fake value.
 *
 * <p>Matched to Timewarp ctw.dll companion module behavior.
 */
public final class PingFixModule extends Module {

    private final SliderSetting fakePing = addSetting(new SliderSetting("Ping", 5.0f, 0.0f, 500.0f));
    private final BoolSetting freezeOnLag = addSetting(new BoolSetting("Freeze on lag", true));

    private int cleanPing;
    private boolean wasLagging;

    public PingFixModule() {
        super("PingFix", "Hide high ping caused by lag modules", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        cleanPing = 0;
        wasLagging = false;
    }

    @Override
    public void onDisable() {
        // no cleanup needed
    }

    @Override
    public void onTick() {
        if (!isEnabled())
            return;

        Object mc = McAccess.getMinecraft();
        if (mc == null)
            return;
        Object player = McAccess.thePlayer(mc);
        if (player == null)
            return;

        Object netHandler = McAccess.getNetHandler(mc);
        if (netHandler == null)
            return;

        // Get the player's unique ID via Entity.getUniqueID() — SRG func_110124_au
        Object uid = McAccess.invoke(player, "func_110124_au", new Class<?>[0]);
        if (uid == null)
            return;

        // Get our own NetworkPlayerInfo via NetHandlerPlayClient.getPlayerInfo(UUID)
        // SRG: func_175153_d, parameter: java.util.UUID
        Object info = McAccess.invoke(netHandler, "func_175153_d",
                new Class<?>[] { uid.getClass() }, uid);
        if (info == null)
            return;

        boolean currentlyLagging = isAnyLagModuleActive();

        if (freezeOnLag.getValue() && currentlyLagging) {
            if (!wasLagging) {
                // Capture the clean ping value before lag started
                cleanPing = McAccess.getInt(info, "field_178856_c");
                wasLagging = true;
            }
            // Freeze at the clean value, but never go below the configured floor
            McAccess.setInt(info, "field_178856_c",
                    Math.max(cleanPing, fakePing.getValue().intValue()));
        } else {
            wasLagging = false;
            int targetPing = fakePing.getValue().intValue();
            McAccess.setInt(info, "field_178856_c", targetPing);
        }
    }

    /** Returns true if any known lag module is actively delaying packets. */
    private boolean isAnyLagModuleActive() {
        // Backtrack — has queued inbound packets
        Module bt = ModuleManager.INSTANCE.getModule("Back Track");
        if (bt instanceof BacktrackModule && bt.isEnabled() && ((BacktrackModule) bt).isLagging())
            return true;

        // Lagrange — has queued outbound packets
        Module lag = ModuleManager.INSTANCE.getModule("Lagrange");
        if (lag instanceof LagrangeModule && lag.isEnabled()
                && ((LagrangeModule) lag).hasQueuedPackets())
            return true;

        // KnockbackDelay — owns the inbound queue
        if (KnockbackDelayModule.isOwningInboundQueue()
                || KnockbackDelayModule.isBlockingBacktrack())
            return true;

        // Blink — is holding outbound packets (the module is always "lagging" while enabled)
        Module blink = ModuleManager.INSTANCE.getModule("Blink");
        if (blink instanceof BlinkModule && blink.isEnabled())
            return true;

        return false;
    }
}
