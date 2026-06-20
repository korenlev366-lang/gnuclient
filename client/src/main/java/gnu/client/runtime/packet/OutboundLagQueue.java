package gnu.client.runtime.packet;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * FIFO outbound packet delay queue (raven {@code BiTrackLagNodeQueue} outbound track).
 * Synchronized {@link ArrayList} — avoids {@code ConcurrentLinkedQueue} weakly-consistent remove bugs.
 *
 * <p>Supports activation gating: {@link #offer(Object)} is a no-op when inactive.
 * Call {@link #activate()} before starting a lag session, {@link #deactivate()}
 * before draining to prevent re-queuing after drain.
 */
public final class OutboundLagQueue {

    private final ArrayList<Entry> queue = new ArrayList<>();
    private volatile boolean active;

    /** Enable queuing — call before setting isLagging=true. */
    public synchronized void activate() {
        active = true;
    }

    /** Disable queuing — call before drainAll() to prevent re-queue after drain. */
    public synchronized void deactivate() {
        active = false;
    }

    /** Whether offer() will accept new packets. */
    public synchronized boolean isActive() {
        return active;
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized void clear() {
        queue.clear();
    }

    public synchronized void offer(Object packet) {
        if (!active || packet == null)
            return;
        queue.add(new Entry(packet, System.currentTimeMillis()));
    }

    /** Batch-release packets older than {@code maxAgeMs} (Raven {@code releaseExpiredPackets}). */
    public synchronized void releaseExpired(long maxAgeMs, Consumer<Object> releaser) {
        releaseExpiredLimited(maxAgeMs, Integer.MAX_VALUE, releaser);
    }

    /** Release at most {@code maxCount} expired packets (Grim Timer — avoids 309ms burst). */
    public synchronized void releaseExpiredLimited(long maxAgeMs, int maxCount, Consumer<Object> releaser) {
        if (queue.isEmpty() || releaser == null || maxCount <= 0)
            return;
        long cutoff = System.currentTimeMillis() - Math.max(0L, maxAgeMs);
        int released = 0;
        Iterator<Entry> it = queue.iterator();
        while (it.hasNext() && released < maxCount) {
            Entry e = it.next();
            if (e.queuedAtMs <= cutoff) {
                it.remove();
                releaser.accept(e.packet);
                released++;
            }
        }
    }

    /** Drain at most {@code maxCount} from head (post-flush spread). */
    public synchronized void drainUpTo(int maxCount, Consumer<Object> releaser) {
        if (releaser == null || maxCount <= 0) {
            return;
        }
        int drained = 0;
        while (!queue.isEmpty() && drained < maxCount) {
            releaser.accept(queue.remove(0).packet);
            drained++;
        }
    }

    /** Drain entire FIFO (post-flush / disable). */
    public synchronized void drainAll(Consumer<Object> releaser) {
        if (releaser == null) {
            queue.clear();
            return;
        }
        while (!queue.isEmpty())
            releaser.accept(queue.remove(0).packet);
    }

    private static final class Entry {
        final Object packet;
        final long queuedAtMs;

        Entry(Object packet, long queuedAtMs) {
            this.packet = packet;
            this.queuedAtMs = queuedAtMs;
        }
    }
}
