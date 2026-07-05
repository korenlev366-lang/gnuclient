package gnu.client.script;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe shared lenience-window counters for setback sync and knockback
 * timing. Updated from {@code onPacketReceive} (Netty thread) and decayed from
 * {@code onPreUpdate} (main thread).
 */
public final class LenienceState {

    public static final LenienceState INSTANCE = new LenienceState();

    private final AtomicInteger setbackTicks = new AtomicInteger(0);
    private final AtomicInteger kbWindow = new AtomicInteger(0);
    private final AtomicInteger explWindow = new AtomicInteger(0);

    private LenienceState() {}

    /** Any tracked lenience window (setback, KB, or explosion). */
    public boolean lenient() {
        return setbackTicks.get() > 0 || kbWindow.get() > 0 || explWindow.get() > 0;
    }

    /** Setback-only — safe for C03 micro sync; do not use KB/expl windows for packet nudges. */
    public boolean setbackLenient() {
        return setbackTicks.get() > 0;
    }

    public int getSetbackTicks() {
        return setbackTicks.get();
    }

    public int getKbWindow() {
        return kbWindow.get();
    }

    public int getExplWindow() {
        return explWindow.get();
    }

    public void setSetbackTicks(int ticks) {
        setbackTicks.set(Math.max(0, ticks));
    }

    public void bumpKbWindow(int ticks) {
        if (ticks < 1)
            return;
        kbWindow.updateAndGet(current -> Math.max(current, ticks));
    }

    public void bumpExplWindow(int ticks) {
        if (ticks < 1)
            return;
        explWindow.updateAndGet(current -> Math.max(current, ticks));
    }

    public void decayTick() {
        setbackTicks.updateAndGet(v -> v > 0 ? v - 1 : 0);
        kbWindow.updateAndGet(v -> v > 0 ? v - 1 : 0);
        explWindow.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    public void reset() {
        setbackTicks.set(0);
        kbWindow.set(0);
        explWindow.set(0);
    }
}
