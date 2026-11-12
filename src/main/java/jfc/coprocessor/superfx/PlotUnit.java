package jfc.coprocessor.superfx;

/**
 * The GSU's pixel hardware: the PLOT and RPIX instructions, and the COLOR/CMODE state they use.
 *
 * <h2>Why a "bitmap" looks like SNES tiles</h2>
 * The SNES PPU can't show a plain bitmap; it only knows 8x8 tiles in bitplane format. So the
 * GSU draws <i>directly into tile format</i>: its frame buffer in RAM is a run of SNES tiles,
 * and the game simply DMAs that block into VRAM and points a background at it. PLOT takes an
 * (x, y) position and works out which tile, which row of it, and which bit in each bitplane the
 * pixel lands in. That bookkeeping is the whole point of the chip's "character-mapped" screen.
 *
 * <h2>Screen layout (SCMR height, or POR's OBJ mode)</h2>
 * Tiles are numbered down the columns, so the tile number for pixel (x, y) is
 * {@code (x / 8) * height_in_tiles + (y / 8)}:
 * <pre>
 *   height 128 (16 tiles):  tile = (x/8)*16 + y/8
 *   height 160 (20 tiles):  tile = (x/8)*20 + y/8
 *   height 192 (24 tiles):  tile = (x/8)*24 + y/8
 *   OBJ mode:               the 16-tile-wide layout of SNES sprite memory, in four
 *                           128x128 quadrants (so the result can be DMA'd as sprite tiles)
 * </pre>
 * A tile takes 8 bytes per bitplane pair as usual: 16 bytes at 2bpp, 32 at 4bpp, 64 at 8bpp.
 * Within it, row r of planes 0/1 is at bytes 2r/2r+1, planes 2/3 sixteen bytes later, and so on.
 *
 * <h2>Plot options (POR, set by CMODE)</h2>
 * <pre>
 *   bit 0  1 = plot color 0 too (otherwise color 0 is "transparent" and skipped)
 *   bit 1  dither (2/4bpp only): on odd (x xor y), use the color's high nibble
 *   bit 2  COLOR/GETC take the source's high nibble into the color's low nibble
 *   bit 3  "freeze high": COLOR/GETC keep the color's high nibble; in 8bpp, the
 *          transparency test looks only at the low nibble
 *   bit 4  OBJ layout (overrides the SCMR height)
 * </pre>
 *
 * <p><i>Simplification:</i> the real chip collects 8 pixels in a "pixel cache" and writes the
 * bitplanes of a whole tile row at once. Writing each pixel straight to RAM gives the same
 * result (the cache only writes bits that were plotted), so we only keep enough of the cache
 * to charge roughly the right time.
 */
final class PlotUnit {

    private final GsuBus bus;

    /** COLR: the color PLOT draws with. */
    int color;
    /** POR: plot option bits (see the class comment). */
    int plotOptions;
    /** SCBR: screen base in RAM, in 1 KiB units. */
    int screenBase;
    /** SCMR bits 1-0: 0 = 2bpp (4 colors), 1 = 4bpp, 2 = 4bpp (unused value), 3 = 8bpp. */
    int colorDepthMode;
    /** SCMR bits 5 and 2: 0 = 128 lines, 1 = 160, 2 = 192, 3 = OBJ layout. */
    int heightMode;

    /** The tile row the last pixel went to (the pixel cache's row), for timing only. */
    private int cachedRowAddress = -1;

    PlotUnit(GsuBus bus) {
        this.bus = bus;
    }

    void reset() {
        color = 0;
        plotOptions = 0;
        screenBase = 0;
        colorDepthMode = 0;
        heightMode = 0;
        cachedRowAddress = -1;
    }

    /** COLOR and GETC: computes the new color register from a source byte (POR bits 2-3). */
    int colorFrom(int source) {
        source &= 0xFF;
        if ((plotOptions & 0x04) != 0) {
            return (color & 0xF0) | (source >> 4);
        }
        if ((plotOptions & 0x08) != 0) {
            return (color & 0xF0) | (source & 0x0F);
        }
        return source;
    }

    /**
     * PLOT: draws one pixel in the current color.
     *
     * @param memoryCycles master cycles one RAM access costs at the current clock speed
     * @return master cycles spent (writing the bitplanes when the pixel cache changes row)
     */
    int plot(int x, int y, int memoryCycles) {
        int bpp = bitsPerPixel();
        int pixelColor = color;
        if ((plotOptions & 0x02) != 0 && bpp != 8) {
            // Dither: a checkerboard of the color's two nibbles, which looks like a third
            // color from a distance. Only meaningful at 2 and 4 bits per pixel.
            if (((x ^ y) & 1) != 0) {
                pixelColor >>= 4;
            }
            pixelColor &= 0x0F;
        }
        if ((plotOptions & 0x01) == 0 && isTransparent(pixelColor, bpp)) {
            return 0;
        }
        int rowAddress = rowAddress(x, y, bpp);
        int bit = 0x80 >> (x & 7); // bit 7 is the leftmost pixel of a tile row
        for (int plane = 0; plane < bpp; plane++) {
            int address = planeAddress(rowAddress, plane);
            int bits = bus.read(address);
            bits = (pixelColor >> plane & 1) != 0 ? bits | bit : bits & ~bit;
            bus.write(address, bits);
        }
        // Moving to another tile row flushes the pixel cache: one RAM write per bitplane.
        int cycles = 0;
        if (rowAddress != cachedRowAddress) {
            cycles = bpp * memoryCycles;
            cachedRowAddress = rowAddress;
        }
        return cycles;
    }

    /** RPIX: reads back the color of a pixel. */
    int readPixel(int x, int y) {
        int bpp = bitsPerPixel();
        int rowAddress = rowAddress(x, y, bpp);
        int bit = 0x80 >> (x & 7);
        int pixelColor = 0;
        for (int plane = 0; plane < bpp; plane++) {
            if ((bus.read(planeAddress(rowAddress, plane)) & bit) != 0) {
                pixelColor |= 1 << plane;
            }
        }
        return pixelColor;
    }

    /** Color 0 is transparent; in 8bpp with "freeze high", any color with a zero low nibble. */
    private boolean isTransparent(int pixelColor, int bpp) {
        if (bpp == 8 && (plotOptions & 0x08) == 0) {
            return (pixelColor & 0xFF) == 0;
        }
        return (pixelColor & 0x0F) == 0;
    }

    int bitsPerPixel() {
        return switch (colorDepthMode) {
            case 0 -> 2;
            case 3 -> 8;
            default -> 4;
        };
    }

    /** The GSU address of bitplanes 0/1 of the tile row that holds pixel (x, y). */
    private int rowAddress(int x, int y, int bpp) {
        int tileNumber = tileNumber(x, y);
        int bytesPerTile = bpp * 8;
        // The frame buffer is always in the first RAM bank ($70); large offsets run on into $71.
        return 0x700000 + (screenBase << 10) + tileNumber * bytesPerTile + (y & 7) * 2;
    }

    /** Planes come in pairs: planes 0/1 interleaved, then 2/3 sixteen bytes later, and so on. */
    private static int planeAddress(int rowAddress, int plane) {
        return rowAddress + (plane >> 1) * 16 + (plane & 1);
    }

    /** Which tile of the character-mapped screen pixel (x, y) is in (see the class comment). */
    private int tileNumber(int x, int y) {
        int column = x >> 3;
        int row = y >> 3;
        boolean objLayout = heightMode == 3 || (plotOptions & 0x10) != 0;
        if (objLayout) {
            // 256x256 as four 128x128 quadrants, each 16x16 tiles in rows of 16:
            // bottom half +512 tiles, right half +256, then 16 tiles per row within the quadrant.
            return (y & 0x80) << 2 | (x & 0x80) << 1 | (row & 0x0F) << 4 | (column & 0x0F);
        }
        int heightInTiles = switch (heightMode) {
            case 0 -> 16;
            case 1 -> 20;
            default -> 24;
        };
        return column * heightInTiles + row;
    }
}
