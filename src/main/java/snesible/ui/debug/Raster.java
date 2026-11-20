package snesible.ui.debug;

/**
 * A pixel buffer to draw the debugger into: {@code 0xRRGGBB} ints, row by row from the top.
 * Just the few primitives the views need (rectangles, text), each clipped to the buffer, so a
 * view can draw partly off-screen without checking.
 */
final class Raster {

    private int[] pixels;
    private int width;
    private int height;

    /** Points the raster at a buffer to draw into (it doesn't copy or own it). */
    void target(int[] pixels, int width, int height) {
        this.pixels = pixels;
        this.width = width;
        this.height = height;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** Fills a rectangle (clipped to the buffer). */
    void fill(int x, int y, int w, int h, int rgb) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(width, x + w);
        int y1 = Math.min(height, y + h);
        for (int row = y0; row < y1; row++) {
            int start = row * width;
            for (int column = x0; column < x1; column++) {
                pixels[start + column] = rgb;
            }
        }
    }

    /** One pixel, if it's inside the buffer. */
    void set(int x, int y, int rgb) {
        if (x >= 0 && y >= 0 && x < width && y < height) {
            pixels[y * width + x] = rgb;
        }
    }

    /** A rectangle's outline, {@code thickness} pixels wide, drawn inside its bounds. */
    void outline(int x, int y, int w, int h, int thickness, int rgb) {
        fill(x, y, w, thickness, rgb);
        fill(x, y + h - thickness, w, thickness, rgb);
        fill(x, y, thickness, h, rgb);
        fill(x + w - thickness, y, thickness, h, rgb);
    }

    /**
     * Draws {@code text} with {@link PixelFont}, each font pixel {@code scale} pixels square,
     * with the top-left of the first character cell at ({@code x}, {@code y}).
     *
     * @return the x just after the text
     */
    int text(String text, int x, int y, int scale, int rgb) {
        for (int i = 0; i < text.length(); i++) {
            int[] rows = PixelFont.glyph(text.charAt(i));
            for (int row = 0; row < rows.length; row++) {
                for (int column = 0; column < 5; column++) {
                    if ((rows[row] & (1 << (4 - column))) != 0) {
                        fill(x + column * scale, y + row * scale, scale, scale, rgb);
                    }
                }
            }
            x += PixelFont.CELL_WIDTH * scale;
        }
        return x;
    }

    /** The width {@link #text} would take. */
    static int textWidth(String text, int scale) {
        return text.length() * PixelFont.CELL_WIDTH * scale;
    }
}
