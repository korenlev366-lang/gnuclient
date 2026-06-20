package gnu.client.runtime.packet;

import gnu.client.common.GnuLog;

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
