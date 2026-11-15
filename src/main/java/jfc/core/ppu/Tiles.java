package jfc.core.ppu;

/**
 * Decodes pixels from SNES tile graphics.
 *
 * <p>All SNES graphics are built from 8x8 tiles stored as <b>bitplanes</b>: each pixel's color
 * number is split into bits, and each bit lives in a different plane. The planes are paired
 * in 16-bit VRAM words, one word per tile row:
 * <pre>
 *   word = (plane 1 bits for 8 pixels) &lt;&lt; 8 | (plane 0 bits for 8 pixels)
 *   bit 7 of each byte is the leftmost pixel
 * </pre>
 * <ul>
 *   <li>2 bits per pixel (4 colors): 8 words per tile, planes 0+1.</li>
 *   <li>4 bpp (16 colors): 16 words, planes 0+1 for all rows, then planes 2+3.</li>
 *   <li>8 bpp (256 colors): 32 words, four such plane pairs.</li>
 * </ul>
 * This layout comes from the NES (whose 2bpp tiles are the same format), and it lets the PPU
 * fetch two planes for a row in one VRAM read.
 */
final class Tiles {

    private Tiles() {
    }

    /** Size of one 8x8 tile in VRAM words at the given bits per pixel. */
    static int wordsPerTile(int bitsPerPixel) {
        return bitsPerPixel * 4;
    }

    /**
     * Returns the color number (0 = transparent) of one pixel.
     *
     * @param tileAddress VRAM word address of the tile
     * @param row         0-7, top to bottom
     * @param column      0-7, left to right
     */
    static int pixel(int[] vram, int tileAddress, int row, int column, int bitsPerPixel) {
        int bit = 7 - column;
        int color = 0;
        for (int plane = 0; plane < bitsPerPixel; plane += 2) {
            // Plane pair (plane, plane+1) starts 8 words further on for each pair.
            int word = vram[(tileAddress + plane * 4 + row) & 0x7FFF];
            color |= ((word >> bit) & 1) << plane;
            color |= ((word >> (bit + 8)) & 1) << (plane + 1);
        }
        return color;
    }
}
