package snesible.core.ppu;

/**
 * The PPU's two "windows": horizontal spans that can hide parts of layers.
 *
 * <p>Each window is just a left and right edge ($2126-$2129), the same on every line. Changing
 * the edges per line with HDMA makes shapes: circles, spotlights, SMW's keyhole, the
 * "iris" wipes. Each layer (BG1-BG4, sprites, and the special "color window" used by color
 * math) chooses:
 * <ul>
 *   <li>whether window 1 and/or window 2 apply to it,</li>
 *   <li>for each, whether it means the inside or (inverted) the outside of the span,</li>
 *   <li>how two windows combine: OR, AND, XOR or XNOR.</li>
 * </ul>
 * The result is a per-pixel "masked" flag. Whether that hides the layer on the main or
 * sub screen is decided separately by TMW/TSW ($212E/$212F).
 *
 * <p>Settings nibble per layer ($2123-$2125): bit 0 = invert window 1, bit 1 = enable window 1,
 * bit 2 = invert window 2, bit 3 = enable window 2.
 */
final class WindowMasks {

    static final int COLOR_WINDOW = 5;
    private static final int LAYERS = 6;

    /** {@code masked[layer][x]}: the windows cover this pixel of this layer. */
    final boolean[][] masked = new boolean[LAYERS][LayerLine.WIDTH];

    void compute(Ppu ppu) {
        for (int layer = 0; layer < LAYERS; layer++) {
            int settings = ppu.windowSettings[layer];
            boolean useWindow1 = (settings & 0x02) != 0;
            boolean invert1 = (settings & 0x01) != 0;
            boolean useWindow2 = (settings & 0x08) != 0;
            boolean invert2 = (settings & 0x04) != 0;
            int logic = ppu.windowLogic[layer];
            boolean[] row = masked[layer];

            for (int x = 0; x < LayerLine.WIDTH; x++) {
                boolean in1 = (x >= ppu.window1Left && x <= ppu.window1Right) != invert1;
                boolean in2 = (x >= ppu.window2Left && x <= ppu.window2Right) != invert2;
                if (useWindow1 && useWindow2) {
                    row[x] = switch (logic) {
                        case 0 -> in1 || in2;
                        case 1 -> in1 && in2;
                        case 2 -> in1 != in2;
                        default -> in1 == in2;
                    };
                } else if (useWindow1) {
                    row[x] = in1;
                } else if (useWindow2) {
                    row[x] = in2;
                } else {
                    row[x] = false;
                }
            }
        }
    }
}
