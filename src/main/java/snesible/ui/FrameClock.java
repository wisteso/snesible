package snesible.ui;

/**
 * Waits until each frame is due, so frames come at an even, exact rate (60.10 per second for
 * an NTSC console).
 *
 * <p>Deadlines are kept on an absolute timeline: frame n is due at {@code start + n * period},
 * so small delays don't accumulate and the average rate is exact. If the program falls far
 * behind (the window was dragged, the PC was busy), the timeline restarts from now instead of
 * racing to catch up.
 *
 * <p>{@code Thread.sleep} on Windows wakes up as much as 2.5 ms late (measured), which would
 * make frames uneven. So the clock sleeps until shortly before the deadline and then waits out
 * the last stretch by checking the time in a loop.
 */
public final class FrameClock {

    /** Stop sleeping this long before a deadline, and wait the rest out precisely. */
    private static final long SPIN_NANOS = 3_000_000;

    private final long periodNanos;
    private long nextDeadline;

    public FrameClock(double framesPerSecond) {
        this.periodNanos = Math.round(1_000_000_000.0 / framesPerSecond);
        this.nextDeadline = System.nanoTime() + periodNanos;
    }

    /** Returns when the next frame is due (immediately, if it already is). */
    public void waitForNextFrame() {
        long now = System.nanoTime();
        if (now - nextDeadline > 5 * periodNanos) {
            nextDeadline = now; // far behind: start a new timeline rather than rush
        }
        long remaining;
        while ((remaining = nextDeadline - System.nanoTime()) > 0) {
            if (remaining > SPIN_NANOS) {
                sleepNanos(remaining - SPIN_NANOS);
            } else {
                Thread.onSpinWait();
            }
        }
        nextDeadline += periodNanos;
    }

    private static void sleepNanos(long nanos) {
        try {
            Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
