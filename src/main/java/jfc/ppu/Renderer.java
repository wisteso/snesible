package jfc.ppu;

import java.util.Arrays;

/**
 * Turns the PPU's state into pixels, one scanline at a time.
 *
 * <p>For each line, every enabled layer (up to four backgrounds plus sprites) is drawn into its
 * own {@link LayerLine}. Then, for each pixel, the layers are checked in front-to-back order
 * and the first opaque one wins. If none is opaque, the backdrop (color 0) shows through.
 *
 * <h2>Background modes</h2>
 * The mode ($2105) decides how many backgrounds there are and how many colors their tiles have:
 * <pre>
 *   mode  BG1   BG2   BG3   BG4   typical use
 *    0    2bpp  2bpp  2bpp  2bpp  four simple layers
 *    1    4bpp  4bpp  2bpp   -    by far the most common (SMW, Zelda, Metroid)
 *    2    4bpp  4bpp   -     -    with offset-per-tile scrolling*
 *    3    8bpp  4bpp   -     -    256-color artwork
 *    4    8bpp  2bpp   -     -    256 colors plus offset-per-tile*
 *    5    4bpp  2bpp   -     -    512-pixel hi-res*
 *    6    4bpp   -     -     -    hi-res plus offset-per-tile*
 *    7    rotation/scaling background (Milestone 6)
 * </pre>
 * <i>* Offset-per-tile and hi-res aren't implemented yet; those modes draw as plain layers.</i>
 *
 * <h2>Priority</h2>
 * Each background tile has a priority bit and each sprite has 2 priority bits. The mode decides
 * how those interleave; see {@link #priorityOrder}.
 */
final class Renderer {

    /** Enough lines for overscan mode. */
    static final int MAX_LINES = 239;
    static final int WIDTH = 256;

    private static final int SPRITES = 4;

    /** Bits per pixel of BG1-BG4 in each mode; 0 means the layer doesn't exist. */
    private static final int[][] BITS_PER_PIXEL = {
        {2, 2, 2, 2},
        {4, 4, 2, 0},
        {4, 4, 0, 0},
        {8, 4, 0, 0},
        {8, 2, 0, 0},
        {4, 2, 0, 0},
        {4, 0, 0, 0},
        {0, 0, 0, 0},
    };

    /**
     * Front-to-back drawing order per mode, as {layer, priority} pairs. Layers 0-3 are BG1-BG4,
     * layer 4 is sprites. "BG1 high" means BG1 tiles with their priority bit set.
     */
    private static final int[][][] PRIORITY_ORDERS = {
        // Mode 0: S3 BG1h BG2h S2 BG1l BG2l S1 BG3h BG4h S0 BG3l BG4l
        {{4, 3}, {0, 1}, {1, 1}, {4, 2}, {0, 0}, {1, 0}, {4, 1}, {2, 1}, {3, 1}, {4, 0}, {2, 0}, {3, 0}},
        // Mode 1: S3 BG1h BG2h S2 BG1l BG2l S1 BG3h S0 BG3l
        {{4, 3}, {0, 1}, {1, 1}, {4, 2}, {0, 0}, {1, 0}, {4, 1}, {2, 1}, {4, 0}, {2, 0}},
        // Modes 2-5: S3 BG1h S2 BG2h S1 BG1l S0 BG2l
        {{4, 3}, {0, 1}, {4, 2}, {1, 1}, {4, 1}, {0, 0}, {4, 0}, {1, 0}},
        {{4, 3}, {0, 1}, {4, 2}, {1, 1}, {4, 1}, {0, 0}, {4, 0}, {1, 0}},
        {{4, 3}, {0, 1}, {4, 2}, {1, 1}, {4, 1}, {0, 0}, {4, 0}, {1, 0}},
        {{4, 3}, {0, 1}, {4, 2}, {1, 1}, {4, 1}, {0, 0}, {4, 0}, {1, 0}},
        // Mode 6: S3 BG1h S2 S1 BG1l S0
        {{4, 3}, {0, 1}, {4, 2}, {4, 1}, {0, 0}, {4, 0}},
        // Mode 7 (Milestone 6): sprites only for now
        {{4, 3}, {4, 2}, {4, 1}, {4, 0}},
    };

    /** Mode 1 with the BG3 priority bit: BG3's high tiles jump to the very front. */
    private static final int[][] MODE1_BG3_FRONT_ORDER =
        {{2, 1}, {4, 3}, {0, 1}, {1, 1}, {4, 2}, {0, 0}, {1, 0}, {4, 1}, {4, 0}, {2, 0}};

    private final Ppu ppu;
    private final LayerLine[] layers = {
        new LayerLine(), new LayerLine(), new LayerLine(), new LayerLine(), new LayerLine(),
    };
    private final SpriteLayer sprites = new SpriteLayer();
    private final int[] frameBuffer = new int[WIDTH * MAX_LINES];

    /** Precomputed 15-bit color to 24-bit RGB, for each of the 16 brightness levels. */
    private final int[][] colorTable = new int[16][1 << 15];

    Renderer(Ppu ppu) {
        this.ppu = ppu;
        buildColorTable();
    }

    int[] frameBuffer() {
        return frameBuffer;
    }

    /** Draws picture line {@code y} (0 = top line) into the frame buffer. */
    void renderLine(int y) {
        int rowStart = y * WIDTH;
        if (ppu.forcedBlank) {
            Arrays.fill(frameBuffer, rowStart, rowStart + WIDTH, 0);
            return;
        }

        int[] bitsPerPixel = BITS_PER_PIXEL[ppu.bgMode];
        for (int bg = 0; bg < 4; bg++) {
            boolean enabled = bitsPerPixel[bg] != 0 && (ppu.mainScreenLayers & (1 << bg)) != 0;
            if (enabled) {
                // Mode 0 gives each background its own 32-color slice of CGRAM.
                int paletteOffset = ppu.bgMode == 0 ? bg * 32 : 0;
                ppu.backgrounds[bg].renderLine(ppu.vram, y, bitsPerPixel[bg], paletteOffset, layers[bg]);
            } else {
                layers[bg].clear();
            }
        }
        if ((ppu.mainScreenLayers & 0x10) != 0) {
            sprites.renderLine(ppu, y, layers[SPRITES]);
        } else {
            layers[SPRITES].clear();
        }

        int[][] order = priorityOrder();
        int[] colors = colorTable[ppu.brightness];
        for (int x = 0; x < WIDTH; x++) {
            frameBuffer[rowStart + x] = colors[ppu.cgram[frontmostColor(order, x)]];
        }
    }

    private int[][] priorityOrder() {
        if (ppu.bgMode == 1 && ppu.bg3HighPriority) {
            return MODE1_BG3_FRONT_ORDER;
        }
        return PRIORITY_ORDERS[ppu.bgMode];
    }

    /** The CGRAM index of the frontmost opaque layer at {@code x}, or 0 for the backdrop. */
    private int frontmostColor(int[][] order, int x) {
        for (int[] slot : order) {
            LayerLine layer = layers[slot[0]];
            int color = layer.color[x];
            if (color != LayerLine.TRANSPARENT && layer.priority[x] == slot[1]) {
                return color;
            }
        }
        return 0;
    }

    /**
     * SNES colors are 5 bits per channel. To get 8 bits we copy the top 3 bits into the bottom,
     * so 0 maps to 0 and 31 to 255. Master brightness then scales linearly.
     */
    private void buildColorTable() {
        for (int brightness = 0; brightness < 16; brightness++) {
            for (int color = 0; color < (1 << 15); color++) {
                int r = expand(color & 0x1F) * brightness / 15;
                int g = expand((color >> 5) & 0x1F) * brightness / 15;
                int b = expand((color >> 10) & 0x1F) * brightness / 15;
                colorTable[brightness][color] = (r << 16) | (g << 8) | b;
            }
        }
    }

    private static int expand(int fiveBits) {
        return (fiveBits << 3) | (fiveBits >> 2);
    }
}
