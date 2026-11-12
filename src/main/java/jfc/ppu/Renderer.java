package jfc.ppu;

import java.util.Arrays;

/**
 * Turns the PPU's state into pixels, one scanline at a time.
 *
 * <h2>The pipeline for one line</h2>
 * <ol>
 *   <li><b>Layers:</b> every background and the sprites are drawn into their own
 *       {@link LayerLine}, with mosaic applied to backgrounds that use it.</li>
 *   <li><b>Windows:</b> the per-pixel window masks are worked out ({@link WindowMasks}).</li>
 *   <li><b>Two screens:</b> the layers are combined twice, using each mode's priority order.
 *       The <i>main screen</i> uses the layers enabled in TM ($212C), the <i>sub screen</i>
 *       those in TS ($212D). Each screen's windowed layers (TMW/TSW) are hidden where masked.</li>
 *   <li><b>Color math:</b> each main screen pixel may be added to or subtracted from the sub
 *       screen pixel (or a fixed color), optionally halved. This gives transparency, shadows,
 *       fades and colored gradients.</li>
 *   <li><b>Brightness:</b> the master brightness scales the final color.</li>
 * </ol>
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
 *    7    one rotating/scaling 8bpp layer (see Mode7Layer)
 * </pre>
 * <i>* Offset-per-tile works in modes 2 and 4 (see BackgroundLayer). Hi-res isn't implemented
 * yet, so modes 5 and 6 draw as 256-pixel layers, and mode 6 ignores its offset table.</i>
 */
final class Renderer {

    /** Enough lines for overscan mode. */
    static final int MAX_LINES = 239;
    static final int WIDTH = 256;

    private static final int SPRITES = 4;
    /** Marks a screen pixel that no layer covers, so the backdrop shows. */
    private static final int BACKDROP = 5;

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
        // Mode 7: S3 S2 BG2h S1 BG1 S0 BG2l (BG2 only exists with EXTBG)
        {{4, 3}, {4, 2}, {1, 1}, {4, 1}, {0, 0}, {4, 0}, {1, 0}},
    };

    /** Mode 1 with the BG3 priority bit: BG3's high tiles jump to the very front. */
    private static final int[][] MODE1_BG3_FRONT_ORDER =
        {{2, 1}, {4, 3}, {0, 1}, {1, 1}, {4, 2}, {0, 0}, {1, 0}, {4, 1}, {4, 0}, {2, 0}};

    private final Ppu ppu;
    private final LayerLine[] layers = {
        new LayerLine(), new LayerLine(), new LayerLine(), new LayerLine(), new LayerLine(),
    };
    private final SpriteLayer sprites = new SpriteLayer();
    private final Mode7Layer mode7 = new Mode7Layer();
    private final WindowMasks windows = new WindowMasks();
    private final int[] frameBuffer = new int[WIDTH * MAX_LINES];

    // The two screens, after priority resolution: which layer won each pixel, and its CGRAM index.
    private final int[] mainSource = new int[WIDTH];
    private final int[] mainColor = new int[WIDTH];
    private final int[] subSource = new int[WIDTH];
    private final int[] subColor = new int[WIDTH];

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

        drawLayers(y);
        windows.compute(ppu);
        int[][] order = priorityOrder();
        resolveScreen(order, ppu.mainScreenLayers, ppu.mainScreenWindowedLayers, mainSource, mainColor);
        resolveScreen(order, ppu.subScreenLayers, ppu.subScreenWindowedLayers, subSource, subColor);

        int[] toRgb = colorTable[ppu.brightness];
        for (int x = 0; x < WIDTH; x++) {
            frameBuffer[rowStart + x] = toRgb[applyColorMath(x)];
        }
    }

    // =============================================================================================
    // Step 1: draw the layers
    // =============================================================================================

    private void drawLayers(int y) {
        int usedLayers = ppu.mainScreenLayers | ppu.subScreenLayers;
        // Vertical mosaic: every line of a block repeats the block's first line.
        int mosaicY = y - (y % ppu.mosaicSize);

        if (ppu.bgMode == 7) {
            boolean bg1Mosaic = (ppu.mosaicLayers & 1) != 0;
            mode7.renderLine(ppu, bg1Mosaic ? mosaicY : y, layers[0], layers[1]);
            applyHorizontalMosaic(0);
            layers[2].clear();
            layers[3].clear();
        } else {
            int[] bitsPerPixel = BITS_PER_PIXEL[ppu.bgMode];
            // In modes 2 and 4, BG3's map is the offset-per-tile table for BG1 and BG2.
            boolean offsetPerTile = ppu.bgMode == 2 || ppu.bgMode == 4;
            BackgroundLayer offsetTable = offsetPerTile ? ppu.backgrounds[2] : null;
            for (int bg = 0; bg < 4; bg++) {
                boolean used = bitsPerPixel[bg] != 0 && (usedLayers & (1 << bg)) != 0;
                if (!used) {
                    layers[bg].clear();
                    continue;
                }
                boolean mosaic = (ppu.mosaicLayers & (1 << bg)) != 0;
                // Mode 0 gives each background its own 32-color slice of CGRAM.
                int paletteOffset = ppu.bgMode == 0 ? bg * 32 : 0;
                ppu.backgrounds[bg].renderLine(ppu.vram, mosaic ? mosaicY : y, bitsPerPixel[bg],
                        paletteOffset, offsetTable, ppu.bgMode == 4, layers[bg]);
                if (mosaic) {
                    applyHorizontalMosaic(bg);
                }
            }
        }

        if ((usedLayers & 0x10) != 0) {
            sprites.renderLine(ppu, y, layers[SPRITES]);
        } else {
            layers[SPRITES].clear();
        }
    }

    /** Horizontal mosaic: each block of pixels takes the color of its leftmost pixel. */
    private void applyHorizontalMosaic(int bg) {
        int size = ppu.mosaicSize;
        if (size == 1 || (ppu.mosaicLayers & (1 << bg)) == 0) {
            return;
        }
        LayerLine layer = layers[bg];
        for (int x = 0; x < WIDTH; x++) {
            int blockStart = x - (x % size);
            layer.color[x] = layer.color[blockStart];
            layer.priority[x] = layer.priority[blockStart];
        }
    }

    // =============================================================================================
    // Step 3: combine layers into the main and sub screens
    // =============================================================================================

    private int[][] priorityOrder() {
        if (ppu.bgMode == 1 && ppu.bg3HighPriority) {
            return MODE1_BG3_FRONT_ORDER;
        }
        return PRIORITY_ORDERS[ppu.bgMode];
    }

    /**
     * For each pixel, finds the frontmost opaque layer among {@code enabledLayers}, skipping
     * layers hidden by their window there.
     */
    private void resolveScreen(int[][] order, int enabledLayers, int windowedLayers,
                               int[] source, int[] color) {
        for (int x = 0; x < WIDTH; x++) {
            source[x] = BACKDROP;
            color[x] = 0;
            for (int[] slot : order) {
                int layerIndex = slot[0];
                if ((enabledLayers & (1 << layerIndex)) == 0) {
                    continue;
                }
                LayerLine layer = layers[layerIndex];
                if (layer.color[x] == LayerLine.TRANSPARENT || layer.priority[x] != slot[1]) {
                    continue;
                }
                if ((windowedLayers & (1 << layerIndex)) != 0 && windows.masked[layerIndex][x]) {
                    continue;
                }
                source[x] = layerIndex;
                color[x] = layer.color[x];
                break;
            }
        }
    }

    // =============================================================================================
    // Step 4: color math
    // =============================================================================================

    /**
     * Produces the final 15-bit color of pixel {@code x}.
     *
     * <p>CGWSEL ($2130):
     * <pre>
     *   bits 7-6  black out the main screen: 0 never, 1 outside color window, 2 inside, 3 always
     *   bits 5-4  allow color math:          0 always, 1 inside color window, 2 outside, 3 never
     *   bit 1     math with the sub screen (1) or with the fixed color (0)
     * </pre>
     * CGADSUB ($2131): bit 7 subtract instead of add, bit 6 halve the result, bits 5-0 which
     * main-screen sources take part: backdrop, sprites, BG4, BG3, BG2, BG1. Sprites only
     * take part if they use palettes 4-7, so games can keep some sprites out of the effect.
     */
    private int applyColorMath(int x) {
        int control = ppu.colorMathControl;
        boolean inColorWindow = windows.masked[WindowMasks.COLOR_WINDOW][x];

        int mainColor15 = ppu.cgram[this.mainColor[x]];
        boolean forcedBlack = windowCondition((control >> 6) & 3, inColorWindow, false);
        if (forcedBlack) {
            mainColor15 = 0;
        }

        boolean mathAllowed = windowCondition((control >> 4) & 3, inColorWindow, true);
        if (!mathAllowed || !sourceTakesPartInMath(mainSource[x], this.mainColor[x])) {
            return mainColor15;
        }

        // The other operand is the sub screen pixel or the fixed color. Where the sub screen is
        // transparent, its "backdrop" is the fixed color, and the result isn't halved: halving
        // is meant for blending two layers, not for tinting.
        boolean useSubScreen = (control & 0x02) != 0;
        int otherColor15;
        boolean halve = (ppu.colorMathLayers & 0x40) != 0 && !forcedBlack;
        if (useSubScreen && subSource[x] != BACKDROP) {
            otherColor15 = ppu.cgram[subColor[x]];
        } else {
            otherColor15 = ppu.fixedColor;
            if (useSubScreen) {
                halve = false;
            }
        }
        boolean subtract = (ppu.colorMathLayers & 0x80) != 0;
        return blend(mainColor15, otherColor15, subtract, halve);
    }

    /**
     * Decodes a CGWSEL two-bit window condition. For "black out" the codes mean never /
     * outside / inside / always; for "allow math" they mean always / inside / outside / never.
     */
    private static boolean windowCondition(int code, boolean inWindow, boolean allowMath) {
        if (allowMath) {
            return switch (code) {
                case 0 -> true;
                case 1 -> inWindow;
                case 2 -> !inWindow;
                default -> false;
            };
        }
        return switch (code) {
            case 0 -> false;
            case 1 -> !inWindow;
            case 2 -> inWindow;
            default -> true;
        };
    }

    private boolean sourceTakesPartInMath(int source, int cgramIndex) {
        int layerBits = ppu.colorMathLayers;
        if (source == BACKDROP) {
            return (layerBits & 0x20) != 0;
        }
        if (source == SPRITES) {
            // Sprite colors are 128-255; palettes 4-7 are 192-255.
            return (layerBits & 0x10) != 0 && cgramIndex >= 192;
        }
        return (layerBits & (1 << source)) != 0;
    }

    /** Adds or subtracts two 15-bit colors channel by channel, clamping each to 0-31. */
    private static int blend(int a, int b, boolean subtract, boolean halve) {
        int result = 0;
        for (int shift = 0; shift <= 10; shift += 5) {
            int channelA = (a >> shift) & 0x1F;
            int channelB = (b >> shift) & 0x1F;
            int channel = subtract ? channelA - channelB : channelA + channelB;
            if (halve) {
                channel >>= 1;
            }
            channel = Math.max(0, Math.min(31, channel));
            result |= channel << shift;
        }
        return result;
    }

    // =============================================================================================
    // Step 5: brightness and conversion to RGB
    // =============================================================================================

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
