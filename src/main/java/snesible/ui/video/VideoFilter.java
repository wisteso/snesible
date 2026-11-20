package snesible.ui.video;

import snesible.ui.Frame;
import snesible.ui.video.ntsc.SnesNtsc;

/**
 * Turns a {@link Frame} of raw PPU pixels into the RGB picture a window or screenshot shows,
 * either as sharp pixels or through the NTSC filter.
 *
 * <p>Sharp pixels come out at the frame's own size (256 x 224). The NTSC filter makes each line
 * 602 pixels wide and, as Snes9x does, draws every line twice (602 x 448): that keeps the
 * output pixels roughly square and gives the picture about the 4:3 shape of a TV, while the
 * sharp picture keeps the SNES's square-pixel 8:7.
 *
 * <p>Building the NTSC tables takes a moment, so make one filter per mode and reuse it.
 */
public final class VideoFilter {

    private final VideoMode mode;
    private final boolean scanlines;
    private final SnesNtsc ntsc;
    private int[] entries = new int[0];

    /**
     * @param scanlines with an NTSC mode, darken every second output row a little (Snes9x's
     *                  "NTSC scanlines" option)
     */
    public VideoFilter(VideoMode mode, boolean scanlines) {
        this.mode = mode;
        this.scanlines = scanlines;
        this.ntsc = mode.ntscSetup() == null ? null : new SnesNtsc(mode.ntscSetup());
    }

    public VideoMode mode() {
        return mode;
    }

    public boolean scanlines() {
        return scanlines;
    }

    /** Width of the output picture for a frame {@code width} pixels wide. */
    public int outputWidth(int width) {
        return ntsc == null ? width : SnesNtsc.outWidth(width);
    }

    /** Height of the output picture for a frame {@code height} lines tall. */
    public int outputHeight(int height) {
        return ntsc == null ? height : height * 2;
    }

    /**
     * Draws the frame into {@code out}, which must hold at least
     * {@code outputWidth(width) * outputHeight(height)} pixels (0xRRGGBB, row after row).
     */
    public void apply(Frame frame, int[] out) {
        int count = frame.width() * frame.height();
        if (ntsc == null) {
            SnesColor.toRgb(frame.pixels(), out, count);
            return;
        }
        if (entries.length < count) {
            entries = new int[count];
        }
        int[] pixels = frame.pixels();
        for (int i = 0; i < count; i++) {
            entries[i] = NtscInput.ENTRY_FOR_PIXEL[pixels[i] & 0x7FFFF];
        }
        // Snes9x steps the starting burst phase once per frame (0, 1, 2, 0, ...). The first
        // frame after a reset is number 1, which gets phase 1, as in Snes9x.
        int burstPhase = (int) (frame.number() % SnesNtsc.BURST_COUNT);
        int outWidth = outputWidth(frame.width());
        ntsc.blit(entries, frame.width(), burstPhase, frame.width(), frame.height(),
                out, outWidth, true, scanlines);
    }

    /**
     * Turns a raw PPU pixel (brightness and 15-bit color) into the NTSC table position for
     * its color. Built on first use (2 MiB), only if an NTSC mode is used.
     */
    private static final class NtscInput {

        static final int[] ENTRY_FOR_PIXEL = build();

        /**
         * Applies the master brightness the way Snes9x does before its NTSC filter: each 5-bit
         * channel becomes {@code round(channel * brightness / 15)} (Snes9x's
         * {@code mul_brightness} table holds exactly these values).
         */
        private static int[] build() {
            int[] table = new int[16 << SnesColor.BRIGHTNESS_SHIFT];
            for (int brightness = 0; brightness < 16; brightness++) {
                for (int color = 0; color <= SnesColor.COLOR_MASK; color++) {
                    int r = scale(color & 0x1F, brightness);
                    int g = scale((color >> 5) & 0x1F, brightness);
                    int b = scale((color >> 10) & 0x1F, brightness);
                    table[SnesColor.pack(color, brightness)] = SnesNtsc.entryFor(r, g, b);
                }
            }
            return table;
        }

        /** round(channel * brightness / 15); the product is never exactly halfway. */
        private static int scale(int channel, int brightness) {
            return (2 * channel * brightness + 15) / 30;
        }
    }
}
