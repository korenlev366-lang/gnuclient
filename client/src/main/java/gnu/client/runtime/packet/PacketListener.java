package gnu.client.runtime.packet;

/**
 * Modules register while enabled to intercept outbound/inbound MC packets.
 * Return true to cancel (drop) the packet.
 */
public interface PacketListener {

    /** @return true to cancel send */
    boolean onSend(Object packet);

    /** @return true to cancel receive processing */
    boolean onReceive(Object packet);

    /** Higher runs first in {@link PacketEvents#onSend} (first true cancels send). */
    default int sendPriority() {
        return 0;
    }
}
