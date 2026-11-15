package jfc.core.ppu;

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
 *
 * <h2>Offset-per-tile (modes 2 and 4)</h2>
 * In these modes BG3 isn't drawn. Instead, its tilemap becomes a table that gives each 8-pixel
 * column of the screen its own scroll values for BG1 and BG2. Games use it to bend or tilt a
 * background: Star Fox tilts the horizon as the Arwing banks, and Tetris Attack shifts columns
 * of blocks. Each table entry is a 16-bit word, read from BG3's map at BG3's scroll position:
 * <pre>
 *   bit 15     mode 4 only: 1 = this entry is a vertical offset, 0 = horizontal
 *   bit 14     the entry applies to BG2
 *   bit 13     the entry applies to BG1
 *   bits 9-0   the scroll value that replaces the register's for this column
 * </pre>
 * In mode 2 the table's first row holds horizontal offsets and its second row vertical ones.
 * Mode 4 has only one row, and bit 15 says which scroll each entry replaces. The leftmost column
 * on screen never uses the table. A horizontal offset only replaces the coarse part of the
 * scroll (bits 9-3); the layer keeps its own fine scroll (bits 2-0), so columns stay aligned.
 */
final class BackgroundLayer {

    /** Bit 13 of an offset-per-tile entry: it applies to BG1 (bit 14: to BG2). */
    private static final int OFFSET_APPLIES_TO_BG1 = 0x2000;
    /** Bit 15 of a mode 4 offset-per-tile entry: the offset is vertical. */
    private static final int OFFSET_IS_VERTICAL = 0x8000;

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
     * @param offsetTable    BG3, whose map holds the offset-per-tile table, in modes 2 and 4;
     *                       null in the other modes
     * @param oneRowTable    true in mode 4, where the table has a single row (see above)
     */
    void renderLine(int[] vram, int screenY, int bitsPerPixel, int paletteOffset,
                    BackgroundLayer offsetTable, boolean oneRowTable, LayerLine out) {
        int tileSize = bigTiles ? 16 : 8;
        int mapWidthPixels = tilemapWidthTiles * tileSize;
        int mapHeightPixels = tilemapHeightTiles * tileSize;
        // The PPU uses the beam's line number, and the first visible line is beam line 1. So with
        // a vertical scroll of 0, the top of the picture shows the map's row 1, not row 0. Games
        // allow for this (many scroll by -1), and Super Metroid's HUD is one line off without it.
        int beamLine = screenY + 1;
        int colorsPerPalette = 1 << bitsPerPixel;
        int appliesToThisLayer = OFFSET_APPLIES_TO_BG1 << index;

        for (int screenX = 0; screenX < LayerLine.WIDTH; screenX++) {
            int columnHScroll = hScroll;
            int columnVScroll = vScroll;
            // Offset-per-tile columns follow this layer's fine scroll, so that a column of the
            // table lines up with a column of this layer's tiles. Column 0 is never affected.
            int offsetX = screenX + (hScroll & 7);
            if (offsetTable != null && offsetX >= 8) {
                int tableX = (offsetX - 8) + (offsetTable.hScroll & ~7);
                int firstRow = offsetTable.entryAtPixel(vram, tableX, offsetTable.vScroll);
                if (oneRowTable) {
                    if ((firstRow & appliesToThisLayer) != 0) {
                        if ((firstRow & OFFSET_IS_VERTICAL) != 0) {
                            columnVScroll = firstRow & 0x3FF;
                        } else {
                            columnHScroll = (firstRow & 0x3F8) | (hScroll & 7);
                        }
                    }
                } else {
                    int secondRow = offsetTable.entryAtPixel(vram, tableX, offsetTable.vScroll + 8);
                    if ((firstRow & appliesToThisLayer) != 0) {
                        columnHScroll = (firstRow & 0x3F8) | (hScroll & 7);
                    }
                    if ((secondRow & appliesToThisLayer) != 0) {
                        columnVScroll = secondRow & 0x3FF;
                    }
                }
            }

            int x = (screenX + columnHScroll) & (mapWidthPixels - 1);
            int y = (beamLine + columnVScroll) & (mapHeightPixels - 1);
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
     * The tilemap entry covering map pixel ({@code x}, {@code y}), wrapping at the map's edges.
     * Used to read BG3's offset-per-tile table.
     */
    int entryAtPixel(int[] vram, int x, int y) {
        int tileSize = bigTiles ? 16 : 8;
        int column = (x / tileSize) & (tilemapWidthTiles - 1);
        int row = (y / tileSize) & (tilemapHeightTiles - 1);
        return vram[tilemapEntryAddress(column, row)];
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
