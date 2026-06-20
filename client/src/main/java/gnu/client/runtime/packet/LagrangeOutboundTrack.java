package gnu.client.runtime.packet;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * raven-bS {@code BiTrackLagNodeQueue} — request-driven bidirectional packet lag queue.
 *
 * <p>Each track (inbound/outbound) is a FIFO of {@link Node}s: either {@link AddRequestNode}
 * (marks a lag session start) or {@link PacketNode} (a queued packet). The track processes
 * packets through {@link #tick(Object, EnumLagDirection, Consumer)}:
 *
 * <ul>
 *   <li>If no request is active (or current request has timed out): nodes are popped and
 *       released until a new {@link AddRequestNode} is found, which becomes the active request.
 *   <li>If a request is active and not timed out: the packet is queued (appended as a
 *       {@link PacketNode}) and the caller is told to cancel it.
 * </ul>
 *
 * <p>On game tick, {@link #tick(Consumer, Consumer)} processes timeouts — if the active
 * request has expired, its marker and all following packets are released.
 *
 * <p>Matches raven-bS {@code UnifiedLagHandler} + {@code BiTrackLagNodeQueue} exactly.
 */
public final class LagrangeOutboundTrack {

    private final TrackState outboundState;
    private final TrackState inboundState;

    public LagrangeOutboundTrack() {
        this.outboundState = new TrackState();
        this.inboundState = new TrackState();
    }

    // ── Public API ──────────────────────────────────────────────────────

    public synchronized boolean hasQueuedPackets() {
        return outboundState.hasQueued() || inboundState.hasQueued();
    }

    public synchronized void clear() {
        outboundState.clear();
        inboundState.clear();
    }

    /**
     * Raven {@code requestLag}: adds an {@link AddRequestNode} to the track(s).
     */
    public synchronized void requestLag(LagRequest request) {
        if (request == null) return;
        for (EnumLagDirection direction : request.getDirections()) {
            track(direction).addRequest(request);
        }
    }

    /**
     * Raven {@code UnifiedLagHandler.onSendPacket} / {@code onReceivePacket}:
     * process a packet through the track. Returns true if the packet should be
     * cancelled (queued for later release).
     */
    public synchronized boolean tick(Object packet, EnumLagDirection direction,
                                     Consumer<Object> releaseFn) {
        if (packet == null || direction == null || releaseFn == null)
            return false;
        return track(direction).tick(packet, releaseFn);
    }

    /**
     * Raven {@code GameTickEvent} handler: process both tracks (pop through
     * timed-out requests and release queued packets).
     */
    public synchronized void tick(Consumer<Object> outboundRelease,
                                  Consumer<Object> inboundRelease) {
        if (outboundRelease != null)
            outboundState.tick(outboundRelease);
        if (inboundRelease != null)
            inboundState.tick(inboundRelease);
    }

    /**
     * Raven {@code releaseExpiredPackets}: release packets from within an active
     * session that are older than {@code maxAgeMs}.
     */
    public synchronized void releaseExpiredPackets(EnumLagDirection direction,
                                                    long maxAgeMs,
                                                    Consumer<Object> releaseFn) {
        if (direction == null || releaseFn == null) return;
        track(direction).releaseExpired(maxAgeMs, releaseFn);
    }

    /** Release all queued packets for a direction (force flush). */
    public synchronized void drainAll(EnumLagDirection direction,
                                       Consumer<Object> releaseFn) {
        if (direction == null || releaseFn == null) return;
        track(direction).drainAll(releaseFn);
    }

    // ── Track routing ───────────────────────────────────────────────────

    private TrackState track(EnumLagDirection dir) {
        return dir == EnumLagDirection.INBOUND ? inboundState : outboundState;
    }

    // ── Inner types ─────────────────────────────────────────────────────

    /**
     * Direction-agnostic direction tag (matches raven EnumSet).
     * Used instead of import to keep the class self-contained.
     */
    public enum EnumLagDirection {
        INBOUND, OUTBOUND
    }

    /**
     * Raven {@code LagRequest} — set of directions + timeout.
     * Matches keystrokesmod.lag.api.LagRequest exactly.
     */
    public static final class LagRequest {
        private final java.util.Set<EnumLagDirection> directions;
        private final Timeout timeout;

        public LagRequest(java.util.Set<EnumLagDirection> directions, Timeout timeout) {
            this.directions = directions;
            this.timeout = timeout;
        }

        public java.util.Set<EnumLagDirection> getDirections() {
            return directions;
        }

        public Timeout getTimeout() {
            return timeout;
        }
    }

    /**
     * Raven {@code AbstractTimeout} — timed-out check + forceTimeOut.
     */
    public abstract static class Timeout {
        private volatile boolean forcefullyTimedOut = false;

        protected abstract boolean shouldHaveTimedOut();

        public final boolean isTimedOut() {
            return forcefullyTimedOut || shouldHaveTimedOut();
        }

        public final void forceTimeOut() {
            forcefullyTimedOut = true;
        }
    }

    /** Raven {@code AbstractLagNode} — base node in the track FIFO. */
    private abstract static class Node {}

    /** Raven {@code AddRequestLagNode} — marks a lag session start. */
    private static final class AddRequestNode extends Node {
        final LagRequest request;
        AddRequestNode(LagRequest request) { this.request = request; }
    }

    /** Raven {@code PacketLagNode} — queued packet with timestamp. */
    private static final class PacketNode extends Node {
        final Object packet;
        final long queuedAtMs;
        PacketNode(Object packet) {
            this.packet = packet;
            this.queuedAtMs = System.currentTimeMillis();
        }
    }

    /**
     * Raven {@code BiTrackLagNodeQueue.TrackState} — single direction's FIFO.
     *
     * <p>Exactly matches raven's logic:
     * <ul>
     *   <li>{@code tick(packet)}: add packet to track, then pop+release through any
     *       timed-out requests until hitting an active one
     *   <li>{@code tick()}: no packet — just pop+release through timed-out requests
     *   <li>{@code releaseExpired}: scan queued packets, release those older than cutoff
     * </ul>
     */
    private static final class TrackState {
        private final List<Node> track = new ArrayList<>();
        private LagRequest currentlyAwaiting = null;

        boolean hasQueued() {
            for (Node node : track) {
                if (node instanceof PacketNode) return true;
            }
            return false;
        }

        void clear() {
            track.clear();
            currentlyAwaiting = null;
        }

        void addRequest(LagRequest request) {
            track.add(new AddRequestNode(request));
        }

        /**
         * Process a packet through the track.
         * 1. Queue the packet (if non-null)
         * 2. Pop+release through timed-out requests
         * Returns true if the packet was queued (caller should cancel the original send),
         * false if it was released immediately or there is no active request.
         */
        boolean tick(Object packet, Consumer<Object> releaseFn) {
            // Raven short-circuit: no active session and no pending
            // AddRequestNodes → pass through without queueing.
            if (packet != null && currentlyAwaiting == null && track.isEmpty()) {
                return false;
            }

            if (packet != null) {
                track.add(new PacketNode(packet));
            }

            LagRequest awaiting = currentlyAwaiting;

            try {
                while (awaiting == null || awaiting.getTimeout().isTimedOut()) {
                    Node popped = pop();
                    if (popped == null) {
                        awaiting = null;
                        break;
                    }
                    if (popped instanceof PacketNode) {
                        PacketNode pn = (PacketNode) popped;
                        if (releaseFn != null) releaseFn.accept(pn.packet);
                    } else if (popped instanceof AddRequestNode) {
                        awaiting = ((AddRequestNode) popped).request;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            currentlyAwaiting = awaiting;

            // If we reached here with a non-null packet, the short-circuit
            // didn't fire → packet was queued → return true (cancel).
            return packet != null;
        }

        /**
         * Tick with no packet (game tick processing).
         * Pops through timed-out requests and releases their packets.
         */
        void tick(Consumer<Object> releaseFn) {
            tick(null, releaseFn);
        }

        /**
         * Raven {@code releaseExpiredPackets}: scan queued PacketNodes, release those
         * older than maxAgeMs. Does NOT affect AddRequestNodes or the currentlyAwaiting
         * state — packets are released from within an active session.
         */
        void releaseExpired(long maxAgeMs, Consumer<Object> releaseFn) {
            if (releaseFn == null) return;
            long cutoff = System.currentTimeMillis() - Math.max(0L, maxAgeMs);
            List<PacketNode> toRelease = new ArrayList<>();
            for (Node node : track) {
                if (node instanceof PacketNode) {
                    PacketNode pn = (PacketNode) node;
                    if (pn.queuedAtMs <= cutoff) {
                        toRelease.add(pn);
                    }
                }
            }
            if (toRelease.isEmpty()) return;
            track.removeAll(toRelease);
            for (PacketNode pn : toRelease) {
                releaseFn.accept(pn.packet);
            }
        }

        /** Drain ALL queued packets (force flush — onDisable / blink abort). */
        void drainAll(Consumer<Object> releaseFn) {
            if (releaseFn == null) {
                track.clear();
                currentlyAwaiting = null;
                return;
            }
            Iterator<Node> it = track.iterator();
            while (it.hasNext()) {
                Node node = it.next();
                it.remove();
                if (node instanceof PacketNode) {
                    releaseFn.accept(((PacketNode) node).packet);
                }
                // AddRequestNodes are just removed (request aborted)
            }
            currentlyAwaiting = null;
        }

        private Node pop() {
            return track.isEmpty() ? null : track.remove(0);
        }
    }
}
