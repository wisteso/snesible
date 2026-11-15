package jfc.ppu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import jfc.system.MasterClock;
import jfc.video.SnesColor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Renders tiny scenes set up purely through PPU registers, then checks individual pixels.
 */
class RendererTest {

    private static final int RED = 0x001F;     // 15-bit BGR
    private static final int GREEN = 0x03E0;
    private static final int RGB_RED = 0xFF0000;
    private static final int RGB_GREEN = 0x00FF00;
    private static final int RGB_BLACK = 0x000000;

    private Ppu ppu;

    @BeforeEach
    void setUp() {
        ppu = new Ppu(new MasterClock(false), false);
        ppu.write(0x00, 0x0F);          // display on, full brightness
        setColor(0, 0x0000);            // backdrop: black
        hideAllSprites();
    }

    // ---- Backgrounds --------------------------------------------------------------------------

    @Test
    void drawsABackgroundTileFromItsTilemapEntry() {
        mode0WithBg1Tile(0x0001);

        renderLine(0);

        assertEquals(RGB_RED, pixel(0, 0), "tile pixel (0,0) uses color 1");
        assertEquals(RGB_BLACK, pixel(1, 0), "color 0 is transparent: the backdrop shows");
    }

    @Test
    void horizontalScrollMovesTheMapAndWrapsAround() {
        mode0WithBg1Tile(0x0001);
        ppu.write(0x0D, 0x01);          // BG1HOFS = 1 (low byte, then high byte)
        ppu.write(0x0D, 0x00);

        renderLine(0);

        assertEquals(RGB_BLACK, pixel(0, 0));
        assertEquals(RGB_RED, pixel(255, 0), "the 256-pixel map wraps: x=256 is x=0 again");
    }

    @Test
    void topPictureLineShowsBackgroundRowOneAtZeroScroll() {
        mode0WithBg1Tile(0x0001);         // tile 1 has its pixel in row 1
        ppu.vram[1 * 8] = 0x0000;

        renderLine(0);
        assertEquals(RGB_RED, pixel(0, 0), "beam line 1 shows map row 1");

        ppu.vram[1 * 8 + 1] = 0x0000;     // move the pixel to row 0 ...
        ppu.vram[1 * 8] = 0x0080;
        ppu.write(0x0E, 0xFF);            // ... and scroll up by one (BG1VOFS = -1)
        ppu.write(0x0E, 0x03);
        renderLine(0);
        assertEquals(RGB_RED, pixel(0, 0), "a scroll of -1 brings map row 0 to the top");
    }

    // ---- Offset-per-tile (modes 2 and 4) ------------------------------------------------------

    @Test
    void offsetPerTileGivesAColumnItsOwnVerticalScroll() {
        mode2WithSolidBg1Tile();
        ppu.vram[0x0400 + 4 * 32 + 1] = 0x0001;   // only map row 4 (y 32-39), column 1, is red
        ppu.vram[0x0400 + 4 * 32 + 2] = 0x0001;
        ppu.vram[0x0800 + 32] = 0x2000 | 31;      // table row 1 (V), first column: BG1, scroll 31

        renderLine(0);                            // beam line 1 + 31 = map y 32: row 4

        assertEquals(RGB_RED, pixel(8, 0), "screen column 1 uses the table's first entry");
        assertEquals(RGB_BLACK, pixel(16, 0), "screen column 2 has no entry: register scroll");
    }

    @Test
    void offsetPerTileReplacesTheCoarseHorizontalScrollButNotTheLeftmostColumn() {
        mode2WithSolidBg1Tile();
        ppu.vram[0x0400 + 10] = 0x0001;           // map column 10 (x 80-87) is red
        ppu.vram[0x0400] = 0x0001;                // and so is column 0 (x 0-7)
        ppu.vram[0x0800 + 1] = 0x2000 | 64;       // table row 0 (H), second column: scroll 64

        renderLine(0);

        assertEquals(RGB_RED, pixel(0, 0), "column 0 never uses the table");
        assertEquals(RGB_RED, pixel(16, 0), "screen x 16 + 64 = map x 80");
        assertEquals(RGB_RED, pixel(23, 0));
        assertEquals(RGB_BLACK, pixel(24, 0));
    }

    @Test
    void offsetPerTileEntriesOnlyAffectTheLayersTheyName() {
        mode2WithSolidBg1Tile();
        ppu.vram[0x0400 + 4 * 32 + 1] = 0x0001;
        ppu.vram[0x0800 + 32] = 0x4000 | 31;      // bit 14: for BG2, not BG1

        renderLine(0);

        assertEquals(RGB_BLACK, pixel(8, 0));
    }

    @Test
    void mode4UsesOneTableRowWithBit15ChoosingVertical() {
        mode2WithSolidBg1Tile();
        ppu.write(0x05, 0x04);                    // BGMODE 4: BG1 is 8bpp
        for (int row = 0; row < 8; row++) {       // redo tile 1 as an 8bpp tile (32 words)
            ppu.vram[16 + row] = 0x0000;
            ppu.vram[32 + row] = 0x00FF;
        }
        ppu.vram[0x0400 + 4 * 32 + 1] = 0x0001;
        ppu.vram[0x0800] = 0x8000 | 0x2000 | 31;  // table row 0: vertical, BG1, scroll 31

        renderLine(0);

        assertEquals(RGB_RED, pixel(8, 0));
    }

    @Test
    void horizontalScrollKeepsAllEightBitsOfTheFirstWrite() {
        mode0WithBg1Tile(0x0001);
        ppu.write(0x0D, 0x07);          // BG1HOFS = 7: the low byte has bit 2 set
        ppu.write(0x0D, 0x00);

        renderLine(0);

        assertEquals(RGB_RED, pixel(249, 0), "map x=0 appears at screen x=256-7");
        assertEquals(RGB_BLACK, pixel(253, 0), "not at 256-3, as it would be with bit 2 lost");
    }

    @Test
    void horizontalFlipMirrorsTheTile() {
        mode0WithBg1Tile(0x4001);       // tile 1 with the h-flip bit

        renderLine(0);

        assertEquals(RGB_BLACK, pixel(0, 0));
        assertEquals(RGB_RED, pixel(7, 0));
    }

    // ---- Sprites ------------------------------------------------------------------------------

    @Test
    void drawsASpriteWithTheSpritePalettes() {
        setColor(129, GREEN);           // sprite palette 0, color 1
        ppu.vram[2 * 16] = 0x0080;      // sprite tile 2 (4bpp, 16 words): pixel (0,0) = color 1
        setSprite(0, 10, 0, 2, 0x00);
        ppu.write(0x2C, 0x10);          // main screen: sprites only

        renderLine(0);

        assertEquals(RGB_GREEN, pixel(10, 0));
        assertEquals(RGB_BLACK, pixel(9, 0));
    }

    @Test
    void spriteXIsNineBitsSoItCanHangOffTheLeftEdge() {
        setColor(129, GREEN);
        ppu.vram[2 * 16] = 0x00FF;      // whole top row is color 1
        setSprite(0, 0xFC, 0, 2, 0x00); // X = $1FC = -4 once bit 8 is set below
        setOamHighBits(0, 0b01);
        ppu.write(0x2C, 0x10);

        renderLine(0);

        assertEquals(RGB_GREEN, pixel(3, 0), "the right half of the sprite is visible");
        assertEquals(RGB_BLACK, pixel(4, 0));
    }

    @Test
    void moreThan32SpritesOnALineSetsRangeOver() {
        for (int i = 0; i < 33; i++) {
            setSprite(i, i * 7, 0, 0, 0x00);
        }
        ppu.write(0x2C, 0x10);

        renderLine(0);

        assertNotEquals(0, ppu.read(0x3E) & 0x40);
    }

    // ---- Priority -----------------------------------------------------------------------------

    @Test
    void highPriorityBackgroundTileCoversLowPrioritySprites() {
        setUpBg1AndSpriteOverlapInMode1(1);

        renderLine(0);

        assertEquals(RGB_RED, pixel(0, 0), "mode 1 order: S3 BG1h BG2h S2 BG1l BG2l S1 ...");
    }

    @Test
    void priority3SpriteCoversEveryBackground() {
        setUpBg1AndSpriteOverlapInMode1(3);

        renderLine(0);

        assertEquals(RGB_GREEN, pixel(0, 0));
    }

    // ---- Brightness and blanking --------------------------------------------------------------

    @Test
    void forcedBlankShowsBlack() {
        mode0WithBg1Tile(0x0001);
        ppu.write(0x00, 0x8F);

        renderLine(0);

        assertEquals(RGB_BLACK, pixel(0, 0));
    }

    @Test
    void masterBrightnessScalesColors() {
        mode0WithBg1Tile(0x0001);
        ppu.write(0x00, 0x05);          // brightness 5 of 15

        renderLine(0);

        assertEquals(255 * 5 / 15, pixel(0, 0) >> 16);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /** Mode 0, BG1 tilemap at $0400, tiles at $0000, tile 1 has color 1 at its top-left pixel. */
    private void mode0WithBg1Tile(int tilemapEntry) {
        setColor(1, RED);
        ppu.write(0x05, 0x00);          // BGMODE 0
        ppu.write(0x07, 0x04);          // BG1 tilemap at word $0400, 32x32
        ppu.write(0x0B, 0x00);          // BG1 tiles at word $0000
        ppu.vram[1 * 8 + 1] = 0x0080;   // tile 1 (2bpp, 8 words), row 1, plane 0, leftmost pixel
        ppu.vram[0x0400] = tilemapEntry;
        ppu.write(0x2C, 0x01);          // main screen: BG1
    }

    /**
     * Mode 2 with an empty BG1 map at $0400 and tile 1 a solid block of color 1 (red). BG3's map,
     * the offset-per-tile table, is at $0800 and starts empty. Tests place the red tiles.
     */
    private void mode2WithSolidBg1Tile() {
        setColor(1, RED);
        ppu.write(0x05, 0x02);          // BGMODE 2: BG1 is 4bpp
        ppu.write(0x07, 0x04);          // BG1 tilemap at word $0400
        ppu.write(0x09, 0x08);          // BG3 tilemap (the offset table) at word $0800
        ppu.write(0x0B, 0x00);          // BG1 tiles at word $0000
        for (int row = 0; row < 8; row++) {
            ppu.vram[16 + row] = 0x00FF; // tile 1 (16 words), plane 0 set everywhere: color 1
        }
        ppu.write(0x2C, 0x01);          // main screen: BG1
    }

    private void setUpBg1AndSpriteOverlapInMode1(int spritePriority) {
        setColor(1, RED);
        setColor(129, GREEN);
        ppu.write(0x05, 0x01);          // BGMODE 1: BG1 is 4bpp
        ppu.write(0x07, 0x04);
        ppu.vram[1 * 16 + 1] = 0x0080;  // BG tile 1 (4bpp), row 1
        ppu.vram[0x0400] = 0x2001;      // tile 1, priority bit set
        ppu.write(0x01, 0x02);          // sprite tiles at word $4000
        ppu.vram[0x4000 + 2 * 16] = 0x0080;
        setSprite(0, 0, 0, 2, spritePriority << 4);
        ppu.write(0x2C, 0x11);          // main screen: BG1 and sprites
    }

    private void setColor(int index, int bgr15) {
        ppu.write(0x21, index);
        ppu.write(0x22, bgr15 & 0xFF);
        ppu.write(0x22, bgr15 >> 8);
    }

    /** Moves every sprite to Y=$F0, below the picture, so only the ones a test sets appear. */
    private void hideAllSprites() {
        for (int i = 0; i < 128; i++) {
            setSprite(i, 0, 0xF0, 0, 0);
        }
    }

    private void setSprite(int index, int x, int y, int tile, int attributes) {
        ppu.write(0x02, index * 2);     // OAM word address: 2 words per sprite
        ppu.write(0x03, 0x00);
        ppu.write(0x04, x);
        ppu.write(0x04, y);
        ppu.write(0x04, tile);
        ppu.write(0x04, attributes);
    }

    private void setOamHighBits(int sprite, int bits) {
        int address = 0x200 + sprite / 4;
        ppu.write(0x02, address >> 1);
        ppu.write(0x03, address >> 9);
        ppu.write(0x04, bits << ((sprite % 4) * 2));
    }

    private void renderLine(int y) {
        ppu.renderLine(y);
    }

    private int pixel(int x, int y) {
        return SnesColor.toRgb(ppu.frameBuffer()[y * 256 + x]);
    }
}
