package jfc.ui.video;

/**
 * Turns the PPU's output pixels into the 24-bit RGB a PC monitor shows.
 *
 * <h2>What the PPU produces</h2>
 * The SNES works out every pixel as a 15-bit color, 5 bits each of red, green and blue
 * (stored as {@code 0bbbbbgggggrrrrr}, the same layout as CGRAM). On top of that, the
 * master brightness register INIDISP ($2100) scales the whole picture in 16 steps, 0 (black)
 * to 15 (full). The console's video circuitry then turns the two into analog voltages for the
 * TV. The emulator's core stops before that last step: each frame-buffer pixel holds the color
 * and the brightness that applied to it, packed into one {@code int}:
 * <pre>
 *   bits 18-15  master brightness (0-15)
 *   bits 14-10  blue  (0-31)
 *   bits  9-5   green (0-31)
 *   bits  4-0   red   (0-31)
 * </pre>
 * Keeping the raw values lets each display choose how to show them. This class gives the plain
 * conversion used by the Swing window and PNG screenshots. The NTSC filter instead feeds the
 * values through a model of a composite video signal, and GPU shaders can do the maths in
 * floating point without rounding twice.
 *
 * <h2>The plain conversion</h2>
 * Each 5-bit channel is widened to 8 bits by copying its top 3 bits into the bottom
 * ({@code 0 -> 0}, {@code 31 -> 255}), then scaled linearly by {@code brightness / 15}. The
 * result is precomputed for every possible packed value (2^19 entries, 2 MiB), so converting a
 * pixel is one array lookup.
 */
public final class SnesColor {

    /** Where the 4-bit master brightness sits in a packed pixel. */
    public static final int BRIGHTNESS_SHIFT = 15;

    /** The 15-bit color part of a packed pixel. */
    public static final int COLOR_MASK = 0x7FFF;

    /** Every packed pixel value (brightness and color) to 0xRRGGBB. */
    private static final int[] TO_RGB = buildTable();

    private SnesColor() {
    }

    /** Packs a 15-bit color and a 0-15 master brightness the way the PPU's frame buffer does. */
    public static int pack(int bgr15, int brightness) {
        return (brightness << BRIGHTNESS_SHIFT) | (bgr15 & COLOR_MASK);
    }

    /** The 15-bit color of a packed pixel. */
    public static int color(int pixel) {
        return pixel & COLOR_MASK;
    }

    /** The master brightness (0-15) of a packed pixel. */
    public static int brightness(int pixel) {
        return (pixel >>> BRIGHTNESS_SHIFT) & 0x0F;
    }

    /** One packed pixel as 0xRRGGBB. */
    public static int toRgb(int pixel) {
        return TO_RGB[pixel & 0x7FFFF];
    }

    /**
     * Converts {@code count} packed pixels to 0xRRGGBB.
     *
     * @param from packed pixels, read starting at index 0
     * @param to   receives the RGB values, written starting at index 0
     */
    public static void toRgb(int[] from, int[] to, int count) {
        for (int i = 0; i < count; i++) {
            to[i] = TO_RGB[from[i] & 0x7FFFF];
        }
    }

    private static int[] buildTable() {
        int[] table = new int[16 << BRIGHTNESS_SHIFT];
        for (int brightness = 0; brightness < 16; brightness++) {
            for (int color = 0; color <= COLOR_MASK; color++) {
                int r = expand(color & 0x1F) * brightness / 15;
                int g = expand((color >> 5) & 0x1F) * brightness / 15;
                int b = expand((color >> 10) & 0x1F) * brightness / 15;
                table[pack(color, brightness)] = (r << 16) | (g << 8) | b;
            }
        }
        return table;
    }

    /** Widens a 5-bit channel to 8 bits: 0 stays 0 and 31 becomes 255. */
    private static int expand(int fiveBits) {
        return (fiveBits << 3) | (fiveBits >> 2);
    }
}
