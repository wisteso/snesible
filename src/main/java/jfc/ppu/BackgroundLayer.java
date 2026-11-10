package jfc.ppu;

/**
 * One tile-based background layer (BG1-BG4): its settings, and drawing one line of it.
 *
 * <h2>How a background is built</h2>
 * A background is a <b>tilemap</b>, a grid of 16-bit entries, one per tile:
 * <pre>
 *   bit 15     vertical flip
 *   bit 14     horizontal flip
 *   bit 13     priority (0 = behind, 1 = in front; see the per-mode tables in Renderer)
 *   bits 12-10 palette number
 *   bits 9-0   tile number (in the layer's tile area)
 * </pre>
 * The map is made of 32x32-entry "screens" (1024 words each), arranged 1x1, 2x1, 1x2 or 2x2, so
 * it's 256 or 512 pixels on each side (twice that with 16x16 tiles). The layer is scrolled over
 * the map and wraps around at its edges.
 */
final class BackgroundLayer {

    /** Which background this is, 0-3 for BG1-BG4. */
    final int index;

    /** VRAM word address of the tilemap ($2107-$210A bits 7-2, in $400-word steps). */
    int tilemapBase;
    /** Tilemap shape in screens: 32 or 64 entries wide/high. */
    int tilemapWidthTiles = 32;
    int tilemapHeightTiles = 32;
    /** VRAM word address of the tile graphics ($210B-$210C, in $1000-word steps). */
    int tileBase;
    /** 16x16 tiles, made of four 8x8 tiles: n, n+1 beside it, n+16 and n+17 below. */
    boolean bigTiles;
    /** Scroll position, 10 bits each. */
    int hScroll;
    int vScroll;

    BackgroundLayer(int index) {
        this.index = index;
    }

    /** $2107-$210A BGnSC: {@code aaaaaa yx}: tilemap address and size (x = 2 wide, y = 2 high). */
    void writeTilemapSettings(int value) {
        tilemapBase = (value & 0xFC) << 8;
        tilemapWidthTiles = (value & 0x01) != 0 ? 64 : 32;
        tilemapHeightTiles = (value & 0x02) != 0 ? 64 : 32;
    }

    /**
     * Draws picture line {@code screenY} of this background into {@code out}.
     *
     * @param bitsPerPixel   2, 4 or 8, decided by the background mode
     * @param paletteOffset  added to every color: in mode 0 each BG has its own 32 colors
     */
    void renderLine(int[] vram, int screenY, int bitsPerPixel, int paletteOffset, LayerLine out) {
        int tileSize = bigTiles ? 16 : 8;
        int mapWidthPixels = tilemapWidthTiles * tileSize;
        int mapHeightPixels = tilemapHeightTiles * tileSize;
        // The PPU uses the beam's line number, and the first visible line is beam line 1. So with
        // a vertical scroll of 0, the top of the picture shows the map's row 1, not row 0. Games
        // allow for this (many scroll by -1), and Super Metroid's HUD is one line off without it.
        int y = (screenY + 1 + vScroll) & (mapHeightPixels - 1);
        int colorsPerPalette = 1 << bitsPerPixel;

        for (int screenX = 0; screenX < LayerLine.WIDTH; screenX++) {
            int x = (screenX + hScroll) & (mapWidthPixels - 1);
            int entry = vram[tilemapEntryAddress(x / tileSize, y / tileSize)];

            boolean flipV = (entry & 0x8000) != 0;
            boolean flipH = (entry & 0x4000) != 0;
            int tileNumber = entry & 0x03FF;
            int palette = (entry >> 10) & 0x07;

            // Position inside the (possibly 16x16) tile, after flipping.
            int fineX = x & (tileSize - 1);
            int fineY = y & (tileSize - 1);
            if (flipH) {
                fineX = tileSize - 1 - fineX;
            }
            if (flipV) {
                fineY = tileSize - 1 - fineY;
            }
            // In a 16x16 tile, step to the 8x8 quarter we're in: +1 to the right, +16 below.
            if (fineX >= 8) {
                tileNumber += 1;
            }
            if (fineY >= 8) {
                tileNumber += 16;
            }

            int tileAddress = tileBase + (tileNumber & 0x3FF) * Tiles.wordsPerTile(bitsPerPixel);
            int colorNumber = Tiles.pixel(vram, tileAddress, fineY & 7, fineX & 7, bitsPerPixel);
            if (colorNumber == 0) {
                out.color[screenX] = LayerLine.TRANSPARENT;
            } else {
                // 8bpp tiles use the whole palette, so the palette field doesn't apply to them.
                int paletteStart = bitsPerPixel == 8 ? 0 : palette * colorsPerPalette;
                out.color[screenX] = paletteOffset + paletteStart + colorNumber;
                out.priority[screenX] = (entry >> 13) & 1;
            }
        }
    }

    /**
     * Finds a tile's entry. The map is stored screen by screen, not row by row: in a 64-wide
     * map, columns 32-63 live in the second screen, 1024 words after the first.
     */
    private int tilemapEntryAddress(int column, int row) {
        int screen = 0;
        if (column >= 32) {
            screen += 1;
        }
        if (row >= 32) {
            screen += tilemapWidthTiles == 64 ? 2 : 1;
        }
        int address = tilemapBase + screen * 0x400 + (row & 31) * 32 + (column & 31);
        return address & 0x7FFF;
    }
}
