package jfc.ppu;

import java.util.Arrays;

/**
 * One scanline of one layer (a background or the sprites), before the layers are combined.
 *
 * <p>Each pixel keeps two things: which palette entry it uses, and its priority. The compositor
 * needs the priority to decide which layer wins; it can only resolve that once every layer is
 * drawn.
 */
final class LayerLine {

    static final int WIDTH = 256;
    /** Marks a pixel the layer doesn't cover (color number 0 is always transparent). */
    static final int TRANSPARENT = -1;

    /** CGRAM index (0-255) per pixel, or {@link #TRANSPARENT}. */
    final int[] color = new int[WIDTH];
    /** Priority per pixel: 0-1 for backgrounds, 0-3 for sprites. */
    final int[] priority = new int[WIDTH];

    void clear() {
        Arrays.fill(color, TRANSPARENT);
    }
}
