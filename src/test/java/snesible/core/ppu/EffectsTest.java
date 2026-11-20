package snesible.core.ppu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import snesible.core.system.MasterClock;
import snesible.ui.video.SnesColor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Color math, windows, Mode 7 and mosaic, each checked on a single rendered line. */
class EffectsTest {

    private static final int BLACK = 0x0000;
    private Ppu ppu;

    @BeforeEach
    void setUp() {
        ppu = new Ppu(new MasterClock(false), false);
        ppu.write(0x00, 0x0F); // display on, full brightness
        for (int i = 0; i < 128; i++) {
            ppu.write(0x02, i * 2);
            ppu.write(0x03, 0);
            ppu.write(0x04, 0);
            ppu.write(0x04, 0xF0); // park every sprite below the picture
        }
    }

    // ---- Color math ---------------------------------------------------------------------------

    @Test
    void addsTheFixedColorToTheBackdrop() {
        setColor(0, rgb15(5, 0, 0));
        ppu.write(0x31, 0x20);        // CGADSUB: add, backdrop takes part
        ppu.write(0x32, 0x20 | 10);   // COLDATA: red = 10

        renderLine(0);

        assertEquals(rgb24(15, 0, 0), pixel(0));
    }

    @Test
    void subtractionClampsAtZero() {
        setColor(0, rgb15(8, 20, 0));
        ppu.write(0x31, 0xA0);        // subtract, backdrop
        ppu.write(0x32, 0x60 | 10);   // red and green = 10

        renderLine(0);

        assertEquals(rgb24(0, 10, 0), pixel(0));
    }

    @Test
    void halvingAveragesTwoColors() {
        setColor(0, rgb15(20, 0, 0));
        ppu.write(0x31, 0x60);        // add, halve, backdrop
        ppu.write(0x32, 0x20 | 10);

        renderLine(0);

        assertEquals(rgb24(15, 0, 0), pixel(0));
    }

    @Test
    void blendsTheMainScreenWithTheSubScreen() {
        fillBg1LineWithColor1();
        setColor(1, rgb15(31, 0, 0));       // BG1 uses color 1: red
        ppu.write(0x05, 0x00);              // mode 0: BG2's colors are 32-63
        ppu.write(0x08, 0x08);              // BG2 tilemap at $0800 (all tile 0 too)
        setColor(33, rgb15(0, 31, 0));      // BG2's color 1: green
        ppu.write(0x2C, 0x01);              // main: BG1
        ppu.write(0x2D, 0x02);              // sub: BG2
        ppu.write(0x30, 0x02);              // math with the sub screen
        ppu.write(0x31, 0x01);              // add, BG1 takes part

        renderLine(0);

        assertEquals(rgb24(31, 31, 0), pixel(0), "red + green = yellow");
    }

    // ---- Windows ------------------------------------------------------------------------------

    @Test
    void windowHidesALayerInsideItsSpan() {
        fillBg1LineWithColor1();
        setColor(1, rgb15(31, 0, 0));
        ppu.write(0x23, 0x02);   // BG1 uses window 1, not inverted
        ppu.write(0x26, 10);     // window 1 from x=10...
        ppu.write(0x27, 20);     // ...to x=20
        ppu.write(0x2E, 0x01);   // apply BG1's window on the main screen

        renderLine(0);

        assertEquals(rgb24(31, 0, 0), pixel(9));
        assertEquals(rgb24(0, 0, 0), pixel(10));
        assertEquals(rgb24(0, 0, 0), pixel(20));
        assertEquals(rgb24(31, 0, 0), pixel(21));
    }

    @Test
    void invertedWindowHidesTheOutside() {
        fillBg1LineWithColor1();
        setColor(1, rgb15(31, 0, 0));
        ppu.write(0x23, 0x03);   // window 1, inverted
        ppu.write(0x26, 10);
        ppu.write(0x27, 20);
        ppu.write(0x2E, 0x01);

        renderLine(0);

        assertEquals(rgb24(0, 0, 0), pixel(9));
        assertEquals(rgb24(31, 0, 0), pixel(15));
    }

    // ---- Mode 7 -------------------------------------------------------------------------------

    @Test
    void mode7WithTheIdentityMatrixShowsTheMapUnchanged() {
        ppu.vram[0] = 0x0001;            // map entry (0,0), low byte: tile 1
        ppu.vram[64 + 8] = 0x0500;       // tile 1 pixel (0,1), high byte: color 5
        setColor(5, rgb15(0, 31, 0));
        ppu.write(0x05, 0x07);           // mode 7
        writeMode7(0x1B, 0x0100);        // A = 1.0
        writeMode7(0x1E, 0x0100);        // D = 1.0
        ppu.write(0x2C, 0x01);

        renderLine(0);

        assertEquals(rgb24(0, 31, 0), pixel(0));
        assertEquals(rgb24(0, 0, 0), pixel(1), "tile 1's next pixel is color 0, transparent");
    }

    @Test
    void mode7ScaleOfTwoStretchesPixels() {
        ppu.vram[0] = 0x0001;
        ppu.vram[64] = 0x0500;           // pixel (0,0): at half scale, beam line 1 is map row 0
        setColor(5, rgb15(0, 31, 0));
        ppu.write(0x05, 0x07);
        writeMode7(0x1B, 0x0080);        // A = 0.5: each map pixel covers two screen pixels
        writeMode7(0x1E, 0x0080);
        ppu.write(0x2C, 0x01);

        renderLine(0);

        assertEquals(rgb24(0, 31, 0), pixel(1));
        assertEquals(rgb24(0, 0, 0), pixel(2));
    }

    // ---- Mosaic -------------------------------------------------------------------------------

    @Test
    void mosaicRepeatsTheFirstPixelOfEachBlock() {
        setColor(1, rgb15(31, 0, 0));
        ppu.write(0x05, 0x00);
        ppu.write(0x07, 0x04);           // BG1 tilemap at $0400, tile data at $0000
        ppu.vram[1] = 0x0080;            // tile 0 row 1: only its leftmost pixel is color 1
        ppu.write(0x2C, 0x01);
        ppu.write(0x06, 0x31);           // 4x4 mosaic on BG1

        renderLine(0);

        assertEquals(rgb24(31, 0, 0), pixel(3), "pixels 0-3 copy pixel 0");
        assertEquals(rgb24(0, 0, 0), pixel(4));
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /** Mode 0 BG1 whose first line is color 1 everywhere: every map entry is tile 0. */
    private void fillBg1LineWithColor1() {
        ppu.write(0x05, 0x00);
        ppu.write(0x07, 0x04);           // tilemap at $0400 (all zero: tile 0)
        ppu.vram[1] = 0x00FF;            // tile 0 row 1: all eight pixels color 1
        ppu.write(0x2C, 0x01);
    }

    private void writeMode7(int register, int value) {
        ppu.write(register, value & 0xFF);
        ppu.write(register, value >> 8);
    }

    private void setColor(int index, int bgr15) {
        ppu.write(0x21, index);
        ppu.write(0x22, bgr15 & 0xFF);
        ppu.write(0x22, bgr15 >> 8);
    }

    private static int rgb15(int r, int g, int b) {
        return (b << 10) | (g << 5) | r;
    }

    /** The 24-bit color the renderer produces for 5-bit channels at full brightness. */
    private static int rgb24(int r, int g, int b) {
        return (expand(r) << 16) | (expand(g) << 8) | expand(b);
    }

    private static int expand(int fiveBits) {
        return (fiveBits << 3) | (fiveBits >> 2);
    }

    private void renderLine(int y) {
        ppu.renderLine(y);
    }

    private int pixel(int x) {
        return SnesColor.toRgb(ppu.frameBuffer()[x]);
    }
}
