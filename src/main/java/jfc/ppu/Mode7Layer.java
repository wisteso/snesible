package jfc.ppu;

/**
 * Mode 7: a single background that can be rotated, scaled and skewed.
 *
 * <h2>The map</h2>
 * Mode 7 has its own fixed VRAM layout, using both bytes of each word for different things:
 * <ul>
 *   <li><b>Low bytes</b> of words $0000-$3FFF: a 128x128 tilemap, one byte (tile number 0-255)
 *       per entry. No flip, palette or priority bits.</li>
 *   <li><b>High bytes</b>: 256 tiles of 8x8 pixels, one byte per pixel (8bpp, "chunky", not
 *       planar). Tile t's pixel (x, y) is the high byte of word t*64 + y*8 + x.</li>
 * </ul>
 * So the map is 1024x1024 pixels.
 *
 * <h2>The transform</h2>
 * For each screen pixel the PPU computes a map position with a 2x2 matrix:
 * <pre>
 *   [mapX]   [A B]   [screenX + scrollX - centerX]   [centerX]
 *   [mapY] = [C D] * [screenY + scrollY - centerY] + [centerY]
 * </pre>
 * A-D are signed 8.8 fixed-point numbers ($211B-$211E). For rotation by angle t with scale s
 * they're A = D = cos(t)/s, B = sin(t)/s, C = -sin(t)/s. Changing the matrix per line with
 * HDMA gives the famous perspective floors (F-Zero, Mario Kart).
 *
 * <p>The hardware works it out per line with some truncation ({@code & ~63}). We copy that
 * truncation exactly, since games line their pictures up against it.
 */
final class Mode7Layer {

    void renderLine(Ppu ppu, int screenY, LayerLine bg1, LayerLine bg2) {
        int a = (short) ppu.mode7A;
        int b = (short) ppu.mode7B;
        int c = (short) ppu.mode7C;
        int d = (short) ppu.mode7D;
        int centerX = signExtend13(ppu.mode7CenterX);
        int centerY = signExtend13(ppu.mode7CenterY);
        int scrollX = signExtend13(ppu.mode7HScroll);
        int scrollY = signExtend13(ppu.mode7VScroll);

        boolean flipX = (ppu.mode7Settings & 0x01) != 0;
        boolean flipY = (ppu.mode7Settings & 0x02) != 0;
        // What lies outside the 1024x1024 map: 0-1 = the map repeats, 2 = transparent,
        // 3 = tile 0 repeated forever (used for "infinite" ground in some games).
        int outsideMode = ppu.mode7Settings >> 6;
        boolean extBg = ppu.isMode7ExtBg();

        int y = flipY ? 255 - screenY : screenY;
        int offsetX = clip(scrollX - centerX);
        int offsetY = clip(scrollY - centerY);
        // Map position of screen pixel x=0 on this line, in 8.8 fixed point.
        int originX = ((a * offsetX) & ~63) + ((b * offsetY) & ~63) + ((b * y) & ~63) + (centerX << 8);
        int originY = ((c * offsetX) & ~63) + ((d * offsetY) & ~63) + ((d * y) & ~63) + (centerY << 8);

        for (int screenX = 0; screenX < LayerLine.WIDTH; screenX++) {
            int x = flipX ? 255 - screenX : screenX;
            int mapX = (originX + a * x) >> 8;
            int mapY = (originY + c * x) >> 8;
            // Inside the map means both coordinates are 0-1023: no bits set above bit 9
            // (negative numbers have all their high bits set, so they count as outside too).
            boolean outside = ((mapX | mapY) & ~0x3FF) != 0;

            int tile;
            if (outside && outsideMode == 2) {
                bg1.color[screenX] = LayerLine.TRANSPARENT;
                bg2.color[screenX] = LayerLine.TRANSPARENT;
                continue;
            } else if (outside && outsideMode == 3) {
                tile = 0;
            } else {
                // Tilemap: 128 tiles per row, one per word, in the low byte. (Masking with 127
                // makes positions outside the map wrap around: the "repeat" behavior.)
                tile = ppu.vram[((mapY >> 3) & 127) * 128 + ((mapX >> 3) & 127)] & 0xFF;
            }
            // Tile graphics: 64 words per tile, one pixel per word, in the high byte.
            int pixel = (ppu.vram[tile * 64 + (mapY & 7) * 8 + (mapX & 7)] >> 8) & 0xFF;

            bg1.color[screenX] = pixel == 0 ? LayerLine.TRANSPARENT : pixel;
            bg1.priority[screenX] = 0;
            // EXTBG shows the same pixels as a second layer, using bit 7 as a priority bit and
            // only 128 colors. Games use it to put part of the Mode 7 picture in front of sprites.
            int extColor = pixel & 0x7F;
            bg2.color[screenX] = extBg && extColor != 0 ? extColor : LayerLine.TRANSPARENT;
            bg2.priority[screenX] = pixel >> 7;
        }
    }

    /** Mode 7 scroll and center registers are 13-bit two's complement numbers. */
    private static int signExtend13(int value) {
        return (value << 19) >> 19;
    }

    /**
     * The hardware keeps only 10 bits of this offset plus its sign (bit 13): the low 10 bits are
     * kept and the sign is extended above them.
     */
    private static int clip(int value) {
        return (value & 0x2000) != 0 ? (value | ~0x3FF) : (value & 0x3FF);
    }
}
