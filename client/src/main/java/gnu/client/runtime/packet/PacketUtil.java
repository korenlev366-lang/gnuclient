package gnu.client.runtime.packet;

import gnu.client.common.GnuLog;
import gnu.client.runtime.mc.McAccess;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Outbound release uses raven {@code addToSendQueue} + fast-track skip (no lag re-queue).
 */
public final class PacketUtil {

    private static final ThreadLocal<Boolean> DISPATCHING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Set<Object> SEND_FAST_TRACK = Collections.newSetFromMap(
            Collections.synchronizedMap(new IdentityHashMap<>()));

    private PacketUtil() {}

    public static boolean isDispatching() {
        return Boolean.TRUE.equals(DISPATCHING.get());
    }

    /** raven {@code PacketUtils.consumeSendEventSkip} — skip {@link PacketEvents} listeners, still send. */
    public static boolean consumeFastTrack(Object packet) {
        return packet != null && SEND_FAST_TRACK.remove(packet);
    }

    /**
     * Released lag FIFO packets: {@code addToSendQueue} path + fast-track (raven {@code goThrough}).
     */
    public static void sendPacketReleased(Object packet) {
        if (packet == null)
            return;
        SEND_FAST_TRACK.add(packet);
        McAccess.addToSendQueue(packet);
    }

    public static void sendPacket(Object packet) {
        if (packet == null)
            return;
        try {
            DISPATCHING.set(Boolean.TRUE);
            Object mc = McAccess.getMinecraft();
            Object netHandler = McAccess.getNetHandler(mc);
            Object networkManager = McAccess.getNetworkManager(netHandler);
            McAccess.invoke(networkManager, "func_179290_a",
                    new Class<?>[] { McAccess.gameClass("net.minecraft.network.Packet") }, packet);
        } catch (Throwable t) {
            GnuLog.log("JAVA_ PacketUtil.sendPacket: " + t);
        } finally {
            DISPATCHING.set(Boolean.FALSE);
        }
    }

    /** Resync swing immediately before a delayed {@code C02 ATTACK} (Vulcan BadPackets). */
    public static void sendSwingAnimation() {
        Object packet = McAccess.newInstance(
                "net.minecraft.network.play.client.C0APacketAnimation",
                new Class<?>[0]);
        if (packet != null)
            sendPacket(packet);
    }

    public static void processInbound(Object packet) {
        if (packet == null)
            return;
        Object netHandler = McAccess.getNetHandler(McAccess.getMinecraft());
        if (netHandler == null)
            return;
        try {
            DISPATCHING.set(Boolean.TRUE);
            Object result = McAccess.invoke(packet, "func_148833_a",
                    new Class<?>[] { McAccess.gameClass("net.minecraft.network.INetHandler") }, netHandler);
            if (result == null)
                McAccess.invokeNamed(packet, "processPacket",
                        new Class<?>[] { McAccess.gameClass("net.minecraft.network.INetHandler") }, netHandler);
        } catch (Throwable t) {
            GnuLog.log("JAVA_ PacketUtil.processInbound: " + t);
        } finally {
            DISPATCHING.set(Boolean.FALSE);
        }
    }
}
