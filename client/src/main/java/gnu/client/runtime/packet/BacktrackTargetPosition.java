package gnu.client.runtime.packet;

import gnu.client.runtime.mc.McAccess;

/**
 * Tracks a target's server-side position from inbound {@code S14}/{@code S18} before they are processed.
 * LiquidBounce {@code TrackedEntityPosition} parity for 1.8.9.
 */
public final class BacktrackTargetPosition {

    private double x;
    private double y;
    private double z;
    private boolean valid;

    public synchronized void setBaseFromEntity(Object entity) {
        if (entity == null) {
            valid = false;
            return;
        }
        x = McAccess.getInt(entity, "field_70118_ct") / 32.0;
        y = McAccess.getInt(entity, "field_70117_cu") / 32.0;
        z = McAccess.getInt(entity, "field_70116_cv") / 32.0;
        valid = true;
    }

    public synchronized void reset() {
        valid = false;
    }

    public synchronized boolean isValid() {
        return valid;
    }

    public synchronized double x() {
        return x;
    }

    public synchronized double y() {
        return y;
    }

    public synchronized double z() {
        return z;
    }

    /** Apply one movement packet to the current base (S14 delta or S18 absolute). */
    public synchronized boolean applyPacket(Object packet) {
        if (packet == null)
            return false;
        if (PacketHelper.isEntityTeleport(packet)) {
            x = PacketHelper.s18PosX(packet);
            y = PacketHelper.s18PosY(packet);
            z = PacketHelper.s18PosZ(packet);
            valid = true;
            return true;
        }
        if (!PacketHelper.isEntityPositionUpdate(packet))
            return false;
        x += PacketHelper.s14DeltaX(packet);
        y += PacketHelper.s14DeltaY(packet);
        z += PacketHelper.s14DeltaZ(packet);
        valid = true;
        return true;
    }

    /** Rebuild server-ahead position from entity serverPos + queued but unprocessed packets. */
    public synchronized void rebuildFrom(Object entity, Iterable<Object> queuedPackets) {
        setBaseFromEntity(entity);
        if (!valid || queuedPackets == null)
            return;
        for (Object packet : queuedPackets)
            applyPacket(packet);
    }
}
