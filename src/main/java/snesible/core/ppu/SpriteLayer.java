package snesible.core.ppu;

/**
 * Draws one line of sprites ("objects", or OBJ, in Nintendo's terms).
 *
 * <h2>OAM layout</h2>
 * Each of the 128 sprites has 4 bytes in the low table and 2 bits in the high table:
 * <pre>
 *   byte 0  X position, low 8 bits
 *   byte 1  Y position (the first line the sprite appears on)
 *   byte 2  tile number, low 8 bits
 *   byte 3  vhoopppN: v/h flip, oo = priority 0-3, ppp = palette, N = tile table select
 *   high table, 2 bits per sprite: bit 0 = X bit 8 (so X runs -256..255), bit 1 = large size
 * </pre>
 * Sprites always use 4bpp tiles and the upper half of CGRAM (palettes 8-15, colors 128-255).
 *
 * <h2>Multi-tile sprites</h2>
 * A sprite bigger than 8x8 is a block of 8x8 tiles. Sprite tiles are thought of as a 16x16 grid
 * (a row is 16 tiles), so the tile to the right of {@code t} is {@code t+1} and the one below is
 * {@code t+16}, each wrapping within its row or column.
 *
 * <h2>Per-line limits</h2>
 * The PPU can't draw every sprite on every line. While drawing each line it:
 * <ol>
 *   <li>scans OAM for sprites that touch the line, keeping at most <b>32</b>
 *       (more sets "range over");</li>
 *   <li>fetches their tiles, 8 pixels at a time, at most <b>34</b> tiles
 *       (more sets "time over").</li>
 * </ol>
 * Tiles are fetched starting with the <i>last</i> sprite found, so when the tile budget runs
 * out, it's the highest-priority sprites (lowest OAM index) that lose tiles. These limits cause
 * the flicker some games use to show more sprites than the hardware allows.
 */
final class SpriteLayer {

    private static final int MAX_SPRITES_PER_LINE = 32;
    private static final int MAX_TILES_PER_LINE = 34;
    private static final int SPRITE_PALETTE_START = 128;

    /** Sprite sizes (width, height) for small and large sprites, per OBSEL size select. */
    private static final int[][] SIZES = {
        {8, 8, 16, 16},
        {8, 8, 32, 32},
        {8, 8, 64, 64},
        {16, 16, 32, 32},
        {16, 16, 64, 64},
        {32, 32, 64, 64},
        {16, 32, 32, 64}, // the two non-square modes, rarely used
        {16, 32, 32, 32},
    };

    private final int[] found = new int[MAX_SPRITES_PER_LINE];

    void renderLine(Ppu ppu, int screenY, LayerLine out) {
        out.clear();
        int foundCount = findSpritesOnLine(ppu, screenY);
        int tilesFetched = 0;

        // Fetch (and draw) in reverse order: later sprites first, so earlier ones are drawn on
        // top of them. An earlier sprite always covers a later one wherever they overlap,
        // whatever their priorities against the backgrounds.
        for (int i = foundCount - 1; i >= 0; i--) {
            int sprite = found[i];
            int tilesNeeded = drawSprite(ppu, sprite, screenY, out, MAX_TILES_PER_LINE - tilesFetched);
            tilesFetched += tilesNeeded;
            if (tilesFetched > MAX_TILES_PER_LINE) {
                ppu.timeOver = true;
                break;
            }
        }
    }

    /** Collects up to 32 sprites that touch {@code screenY}, in priority order. */
    private int findSpritesOnLine(Ppu ppu, int screenY) {
        // Normally sprite 0 has the highest priority. With priority rotation enabled, the
        // sprite at the OAM address does, and the order wraps around from there. (The address
        // counts bytes and each sprite has 4, so dividing by 4 gives the sprite number.)
        int first = ppu.objectPriorityRotation ? (ppu.oamReloadAddress >> 2) & 0x7F : 0;
        int count = 0;
        for (int i = 0; i < 128; i++) {
            int sprite = (first + i) & 0x7F;
            int height = height(ppu, sprite);
            int width = width(ppu, sprite);
            // Which row of the sprite falls on this line. Y is 8 bits and wraps, so the
            // subtraction is done modulo 256: a sprite at Y=250 that is 16 pixels tall shows its
            // rows 6-15 on lines 0-9. A row of 256-height or more means "not on this line".
            int row = (screenY - ppu.oam[sprite * 4 + 1]) & 0xFF;
            int x = x(ppu, sprite);
            boolean onLine = row < height;
            boolean onScreen = x > -width && x < 256;
            if (onLine && onScreen) {
                if (count == MAX_SPRITES_PER_LINE) {
                    ppu.rangeOver = true;
                    break;
                }
                found[count++] = sprite;
            }
        }
        return count;
    }

    /**
     * Draws the row of {@code sprite} that falls on {@code screenY}.
     *
     * @param tileBudget tiles still allowed on this line
     * @return how many 8-pixel tiles the sprite needed (it draws at most {@code tileBudget})
     */
    private int drawSprite(Ppu ppu, int sprite, int screenY, LayerLine out, int tileBudget) {
        int attributes = ppu.oam[sprite * 4 + 3];
        boolean flipV = (attributes & 0x80) != 0;
        boolean flipH = (attributes & 0x40) != 0;
        int priority = (attributes >> 4) & 3;
        int palette = (attributes >> 1) & 7;
        boolean secondTable = (attributes & 1) != 0;
        int firstTile = ppu.oam[sprite * 4 + 2];

        int width = width(ppu, sprite);
        int height = height(ppu, sprite);
        int x = x(ppu, sprite);
        int row = (screenY - ppu.oam[sprite * 4 + 1]) & 0xFF;
        if (flipV) {
            row = height - 1 - row;
        }

        int tableBase = ppu.objectTileBase + (secondTable ? ppu.objectTileGap + 0x1000 : 0);
        int tilesNeeded = 0;
        int columns = width / 8;
        for (int column = 0; column < columns; column++) {
            int tileX = x + column * 8;
            if (tileX <= -8 || tileX >= 256) {
                continue; // off-screen tiles aren't fetched
            }
            tilesNeeded++;
            if (tilesNeeded > tileBudget) {
                continue;
            }
            // Flipping a multi-tile sprite mirrors the order of its tiles as well as their pixels.
            int tileColumn = flipH ? columns - 1 - column : column;
            int tileRow = row / 8;
            int tileNumber = (((firstTile >> 4) + tileRow) & 0x0F) << 4
                    | (((firstTile & 0x0F) + tileColumn) & 0x0F);
            int tileAddress = (tableBase + tileNumber * Tiles.wordsPerTile(4)) & 0x7FFF;

            for (int pixel = 0; pixel < 8; pixel++) {
                int screenX = tileX + pixel;
                if (screenX < 0 || screenX >= 256) {
                    continue;
                }
                int tileColumnPixel = flipH ? 7 - pixel : pixel;
                int colorNumber = Tiles.pixel(ppu.vram, tileAddress, row & 7, tileColumnPixel, 4);
                if (colorNumber != 0) {
                    out.color[screenX] = SPRITE_PALETTE_START + palette * 16 + colorNumber;
                    out.priority[screenX] = priority;
                }
            }
        }
        return tilesNeeded;
    }

    private static boolean isLarge(Ppu ppu, int sprite) {
        int highBits = ppu.oam[0x200 + sprite / 4] >> ((sprite % 4) * 2);
        return (highBits & 0x02) != 0;
    }

    private static int width(Ppu ppu, int sprite) {
        return SIZES[ppu.objectSizeSelect][isLarge(ppu, sprite) ? 2 : 0];
    }

    private static int height(Ppu ppu, int sprite) {
        return SIZES[ppu.objectSizeSelect][isLarge(ppu, sprite) ? 3 : 1];
    }

    /** X is 9 bits, signed: 256-511 mean -256 to -1, partly or fully off the left edge. */
    private static int x(Ppu ppu, int sprite) {
        int highBits = ppu.oam[0x200 + sprite / 4] >> ((sprite % 4) * 2);
        int x = ppu.oam[sprite * 4] | ((highBits & 1) << 8);
        return x >= 256 ? x - 512 : x;
    }
}
