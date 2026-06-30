package gnu.client.runtime.packet;

import gnu.client.common.GnuLog;
import gnu.client.runtime.mc.McAccess;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Static entry points injected by JVMTI bytecode hooks in NetworkManager.
 */
public final class PacketEvents {

    private static final List<PacketListener> LISTENERS = new CopyOnWriteArrayList<>();

    private PacketEvents() {}

    public static void register(PacketListener listener) {
        if (listener != null && !LISTENERS.contains(listener))
            LISTENERS.add(listener);
    }

    public static void unregister(PacketListener listener) {
        LISTENERS.remove(listener);
    }

    /** Injected at start of NetworkManager.sendPacket. Forward order — last registered runs last (Raven LOWEST). */
    public static boolean onSend(Object packet) {
        if (PacketUtil.consumeFastTrack(packet))
            return false;
        if (PacketUtil.isDispatching() || !PacketHelper.isPacket(packet))
            return false;
        logOrderDiagnostic(packet);
        for (PacketListener listener : LISTENERS) {
            try {
                if (listener.onSend(packet))
                    return true;
            } catch (Throwable t) {
                GnuLog.log("JAVA_ PacketEvents.onSend listener error: " + t);
            }
        }
        return false;
    }

    /** Wire-order probe for AutoBlock PacketOrderB — grep {@code [ORDER]} in logs. */
    private static void logOrderDiagnostic(Object packet) {
        if (PacketHelper.isAttackUseEntity(packet))
            GnuLog.log("[ORDER] tick=" + worldTick() + " C02");
        else if (PacketHelper.isSendUseItem(packet))
            GnuLog.log("[ORDER] tick=" + worldTick() + " C08");
    }

    /** World#getTotalWorldTime (SRG func_82737_E) — same tick for all [ORDER] sends in one game tick. */
    public static long worldTick() {
        Object world = McAccess.theWorld();
        if (world == null)
            return -1;
        Object tick = McAccess.invoke(world, "func_82737_E", new Class<?>[0]);
        return tick instanceof Long ? (Long) tick : -1;
    }

    /** Injected at start of NetworkManager.channelRead0 (after msg load). */
    public static boolean hookChannelRead(Object msg) {
        if (PacketUtil.isDispatching() || !PacketHelper.isPacket(msg))
            return false;
        return onReceive(msg);
    }

    public static boolean onReceive(Object packet) {
        if (PacketUtil.isDispatching())
            return false;
        for (PacketListener listener : LISTENERS) {
            try {
                if (listener.onReceive(packet))
                    return true;
            } catch (Throwable t) {
                GnuLog.log("JAVA_ PacketEvents.onReceive listener error: " + t);
            }
        }
        return false;
    }
}
