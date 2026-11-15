package jfc.core.system;

/**
 * The SNES master clock and the position of the video "beam".
 *
 * <p>Everything in the console runs off one crystal: 21.477 MHz on NTSC machines (21.281 MHz on
 * PAL). Time in this emulator is measured in these <b>master cycles</b>. The CPU takes 6, 8 or
 * 12 of them per bus cycle depending on the address, and the PPU draws one pixel ("dot") every 4.
 *
 * <p>Because the picture is drawn line by line, the console thinks of time as a beam position:
 * <pre>
 *   one scanline = 1364 master cycles = 341 dots (dots 0-339 visible area plus border/blanking)
 *   one frame    = 262 scanlines (NTSC) or 312 (PAL)
 *   lines 1-224  = visible picture (1-239 with overscan enabled)
 *   lines after  = vertical blank, when games update video memory and NMI fires
 * </pre>
 * Many hardware events are tied to that position: NMI at the start of vertical blank, timer IRQs
 * at a programmed H/V position, DRAM refresh at a fixed point on every line, and (later) HDMA and
 * rendering. This class advances time and reports the beam moving to a {@link Listener}.
 *
 * <p><i>Simplification:</i> real hardware stretches two dots per line to 6 cycles and adjusts
 * line length on interlaced frames. We use a uniform 1364-cycle line, which keeps the frame
 * length right on average.
 */
public final class MasterClock {

    public static final int CYCLES_PER_LINE = 1364;
    public static final int CYCLES_PER_DOT = 4;
    public static final int LINES_PER_FRAME_NTSC = 262;
    public static final int LINES_PER_FRAME_PAL = 312;

    /**
     * Once per line the CPU is paused for 40 master cycles while the work RAM chips are
     * refreshed (DRAM forgets its contents unless it's rewritten periodically). That's about 3%
     * of all CPU time, and the pause happens at roughly the same point on every line.
     */
    static final int REFRESH_POSITION = 538;
    static final int REFRESH_CYCLES = 40;

    /** Receives beam-position events. Implemented by {@link Snes}, which forwards them. */
    public interface Listener {
        /** The beam has reached the start (h = 0) of {@code line}. */
        void onLineStart(int line);

        /**
         * The beam has moved from {@code fromH} (inclusive) to {@code toH} (exclusive) on
         * {@code line}, both in master cycles from the start of the line. Spans never cross a
         * line boundary, so listeners can check "did the beam pass position P?" with
         * {@code fromH <= P && P < toH}.
         */
        void onBeamMoved(int line, int fromH, int toH);
    }

    private final int linesPerFrame;
    private Listener listener;

    private long totalCycles;
    private int h;
    private int v;
    private long frameCount;
    /** Cycles other chips have taken from the CPU, still to be added (see {@link #stall}). */
    private int pendingStall;

    public MasterClock(boolean pal) {
        this.linesPerFrame = pal ? LINES_PER_FRAME_PAL : LINES_PER_FRAME_NTSC;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Pauses the CPU for {@code cycles} while something else uses the bus, as HDMA does.
     * Listeners call this while the clock is in the middle of {@link #advance}, so the time
     * can't be added on the spot. It's added to the advance in progress instead.
     */
    public void stall(int cycles) {
        pendingStall += cycles;
    }

    /** Lets time pass. Called by the bus for every CPU cycle and by DMA for each transfer. */
    public void advance(int cycles) {
        while (cycles > 0 || pendingStall > 0) {
            cycles += pendingStall;
            pendingStall = 0;
            // Move at most to the end of the current line, so each span stays within one line.
            int chunk = Math.min(cycles, CYCLES_PER_LINE - h);
            if (h <= REFRESH_POSITION && REFRESH_POSITION < h + chunk) {
                // The refresh pause doesn't consume the caller's cycles. It adds time on top, so
                // whatever the CPU was doing finishes 40 cycles later.
                cycles += REFRESH_CYCLES;
            }
            listener.onBeamMoved(v, h, h + chunk);
            h += chunk;
            totalCycles += chunk;
            cycles -= chunk;
            if (h == CYCLES_PER_LINE) {
                h = 0;
                v++;
                if (v == linesPerFrame) {
                    v = 0;
                    frameCount++;
                }
                listener.onLineStart(v);
            }
        }
    }

    /** Position within the current line, in master cycles (0-1363). */
    public int h() {
        return h;
    }

    /** The horizontal position in dots (0-340), as the PPU's H counter reports it. */
    public int dot() {
        return h / CYCLES_PER_DOT;
    }

    /** The current scanline (0-261 on NTSC). */
    public int v() {
        return v;
    }

    public int linesPerFrame() {
        return linesPerFrame;
    }

    public long totalCycles() {
        return totalCycles;
    }

    public long frameCount() {
        return frameCount;
    }
}
