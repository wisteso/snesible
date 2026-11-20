package jfc.ui;

/**
 * Decides whether a {@link ToolScreen} redraws after this frame, so a slow redraw can't slow
 * the game down.
 *
 * <p>The tool screen is drawn on the same thread as the emulation, between frames. Most views
 * draw in a millisecond or two, but a big memory zoomed out (a 4 MiB ROM: millions of access
 * stamps) can take 15-20 ms, more than a whole frame. So the pacer times each redraw and, when
 * redraws get expensive, spreads them out: one every N frames, where N keeps the redraw cost
 * under {@link #BUDGET_NANOS} per frame on average. {@link ToolScreen#afterFrame()} still
 * runs every frame (that's where the recording advances); only drawing is skipped.
 *
 * <p><b>Debug support:</b> used only with a tool screen.
 */
public final class ToolRedrawPacer {

    /** The average redraw time per frame to stay under (a quarter of a 60 Hz frame). */
    private static final long BUDGET_NANOS = 4_000_000;

    private int framesBetween = 1;
    private int framesSince;

    /** Call once per frame; true if the tool screen should redraw now. */
    public boolean shouldRedraw() {
        framesSince++;
        if (framesSince < framesBetween) {
            return false;
        }
        framesSince = 0;
        return true;
    }

    /** Reports how long the redraw just done took. */
    public void redrawTook(long nanos) {
        framesBetween = (int) Math.max(1, Math.min(15, (nanos + BUDGET_NANOS - 1) / BUDGET_NANOS));
    }
}
