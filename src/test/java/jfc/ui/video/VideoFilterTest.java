package jfc.ui.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntBinaryOperator;
import jfc.ui.Frame;
import org.junit.jupiter.api.Test;

/**
 * Properties of the picture filters, checked on small synthetic frames: sharp pixels convert
 * exactly, and the NTSC filter behaves like the signal it models.
 */
class VideoFilterTest {

    private static final int WIDTH = 256;
    private static final int HEIGHT = 8;
    private static final int WHITE = 0x7FFF;

    // The NTSC tables take a moment to build, so share them between tests.
    private static final VideoFilter COMPOSITE = new VideoFilter(VideoMode.COMPOSITE, false);
    private static final VideoFilter SVIDEO = new VideoFilter(VideoMode.SVIDEO, false);
    private static final VideoFilter RF = new VideoFilter(VideoMode.RF, false);

    @Test
    void sharpPixelsUseThePlainColorConversion() {
        VideoFilter sharp = new VideoFilter(VideoMode.SHARP, false);
        int[] out = render(sharp, (x, y) -> SnesColor.pack(x * 31 / 255, 15), 1);
        assertEquals(256, sharp.outputWidth(256));
        assertEquals(224, sharp.outputHeight(224));
        for (int x = 0; x < WIDTH; x++) {
            assertEquals(SnesColor.toRgb(SnesColor.pack(x * 31 / 255, 15)), out[x]);
        }
    }

    @Test
    void ntscMakes602By448FromAStandardFrame() {
        assertEquals(602, COMPOSITE.outputWidth(256));
        assertEquals(448, COMPOSITE.outputHeight(224));
    }

    @Test
    void flatWhiteAndBlackComeOutWhiteAndBlack() {
        // White comes out as F6FCF6, not FFFFFF: the filter keeps only 4 bits of red and blue,
        // so red and blue 31 become 30. Snes9x shows the same.
        int[] white = render(COMPOSITE, (x, y) -> SnesColor.pack(WHITE, 15), 1);
        int[] black = render(COMPOSITE, (x, y) -> SnesColor.pack(WHITE, 0), 1);
        int width = COMPOSITE.outputWidth(WIDTH);
        for (int x = 20; x < width - 20; x++) {
            assertClose(0xF6FCF6, white[x], 2, "white at x=" + x);
            assertEquals(0, black[x], "black at x=" + x);
        }
    }

    @Test
    void aFlatColorStaysFlat() {
        // The error correction makes every output position of a flat area add up to the same
        // color, so there's no pattern, only the edges of the line differ.
        int color = rgb15(20, 12, 6);
        int[] out = render(COMPOSITE, (x, y) -> SnesColor.pack(color, 15), 1);
        int width = COMPOSITE.outputWidth(WIDTH);
        int middle = out[width / 2];
        for (int x = 20; x < width - 20; x++) {
            assertEquals(middle, out[x], "x=" + x);
        }
    }

    @Test
    void grayStaysGray() {
        int[] out = render(COMPOSITE, (x, y) -> SnesColor.pack(rgb15(16, 16, 16), 15), 1);
        int rgb = out[300];
        int r = rgb >> 16;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        assertClose(r, g, 2, "no tint");
        assertClose(g, b, 2, "no tint");
    }

    @Test
    void fineStripesMakeRainbowsOnCompositeButNotOnSVideo() {
        // Alternating black and white columns put luma detail near the color subcarrier's
        // frequency. A composite TV can't tell that from color; S-Video carries luma on its own
        // wire, so it stays gray (apart from a faint green tint of up to about 8 levels: with 4
        // bits of red and blue but 5 of green, white is slightly green to begin with).
        IntBinaryOperator stripes = (x, y) -> SnesColor.pack(x % 2 == 0 ? WHITE : 0, 15);
        assertTrue(maxSaturation(render(COMPOSITE, stripes, 1)) > 40, "composite shows color");
        assertTrue(maxSaturation(render(SVIDEO, stripes, 1)) <= 10, "S-Video stays gray");
    }

    @Test
    void theColorBurstPhaseMovesEveryLineAndEveryFrame() {
        // Without field merging, the artifact colors depend on where the line's color burst
        // starts: it moves 120 degrees per line, and the starting phase moves every frame.
        IntBinaryOperator stripes = (x, y) -> SnesColor.pack(x % 2 == 0 ? WHITE : 0, 15);
        int width = RF.outputWidth(WIDTH);
        int[] frame1 = render(RF, stripes, 1);
        int[] frame2 = render(RF, stripes, 2);
        int[] frame4 = render(RF, stripes, 4);
        int line0 = 0;
        int line1 = 2 * width; // each line is drawn twice
        int line3 = 6 * width;
        assertNotEquals(slice(frame1, line0, width), slice(frame1, line1, width), "next line differs");
        assertEquals(slice(frame1, line0, width), slice(frame1, line3, width), "3 lines later it repeats");
        assertNotEquals(slice(frame1, line0, width), slice(frame2, line0, width), "next frame differs");
        assertEquals(slice(frame1, line0, width), slice(frame4, line0, width), "3 frames later it repeats");
    }

    @Test
    void scanlinesDarkenEverySecondRowByAnEighth() {
        VideoFilter withScanlines = new VideoFilter(VideoMode.RGB, true);
        int[] out = render(withScanlines, (x, y) -> SnesColor.pack(WHITE, 15), 1);
        int width = withScanlines.outputWidth(WIDTH);
        int bright = out[300];
        int dark = out[width + 300];
        assertEquals(bright, out[2 * width + 300]);
        int channel = bright & 0xFF;
        assertEquals(channel - (channel >> 3), dark & 0xFF);
    }

    @Test
    void masterBrightnessDimsTheNtscPicture() {
        int[] full = render(COMPOSITE, (x, y) -> SnesColor.pack(WHITE, 15), 1);
        int[] half = render(COMPOSITE, (x, y) -> SnesColor.pack(WHITE, 8), 1);
        assertTrue((half[300] & 0xFF) < (full[300] & 0xFF) - 40);
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** Renders a synthetic frame whose pixel at (x, y) is {@code pixel.applyAsInt(x, y)}. */
    private static int[] render(VideoFilter filter, IntBinaryOperator pixel, long frameNumber) {
        int[] pixels = new int[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                pixels[y * WIDTH + x] = pixel.applyAsInt(x, y);
            }
        }
        int[] out = new int[filter.outputWidth(WIDTH) * filter.outputHeight(HEIGHT)];
        filter.apply(new Frame(pixels, WIDTH, HEIGHT, frameNumber), out);
        return out;
    }

    /** The largest difference between a pixel's strongest and weakest channel, away from the edges. */
    private static int maxSaturation(int[] out) {
        int max = 0;
        for (int x = 20; x < 580; x++) {
            int rgb = out[x];
            int r = rgb >> 16;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            max = Math.max(max, Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)));
        }
        return max;
    }

    private static java.util.List<Integer> slice(int[] array, int from, int length) {
        return java.util.Arrays.stream(array, from, from + length).boxed().toList();
    }

    private static int rgb15(int r, int g, int b) {
        return (b << 10) | (g << 5) | r;
    }

    private static void assertClose(int expected, int actual, int tolerance, String message) {
        for (int shift = 0; shift <= 16; shift += 8) {
            int e = (expected >> shift) & 0xFF;
            int a = (actual >> shift) & 0xFF;
            assertTrue(Math.abs(e - a) <= tolerance,
                    message + String.format(": expected %06X, got %06X", expected, actual));
        }
    }
}
