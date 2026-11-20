package snesible.ui.debug;

import snesible.debug.Access;
import snesible.debug.MemorySpace;

/**
 * One memory space drawn as a grid of cells, one per byte, coloured by recent access
 * ({@link HeatPalette}), with smooth zoom and panning.
 *
 * <h2>Layout</h2>
 * Bytes run left to right in rows of {@link #columns}, offset 0 at the top left. The row width
 * is a power of two chosen to make the grid roughly square: 64 KiB is 256 x 256, as in ICU64;
 * 128 KiB is 512 x 256; the SA-1's 2 KiB I-RAM is 64 x 32.
 * The small tables (CGRAM, OAM) use rows of 32 bytes: 16 colours, or 8 sprites.
 *
 * <h2>Zoom</h2>
 * {@link #zoom} is the size of one cell in pixels. Below 1, each pixel stands for a block of
 * cells and shows the newest access of each kind within it, so a single active byte stays
 * visible however far out the view is. From 6 pixels on, a one-pixel gap separates the cells.
 * Milestone 9b will draw values inside the cells once they're big enough.
 *
 * <p>The view keeps its position as {@code originX}/{@code originY}: the cell coordinate shown
 * at the top-left corner, in fractional cells, so zooming around the cursor is exact.
 */
final class HeatMapView {

    private static final double MAX_ZOOM = 96;
    private static final int GRID_GAP_ZOOM = 6;
    private static final int GRID = 0x08080A;
    private static final int OUTSIDE = 0x000000;
    private static final int HOVER = 0xFFC020;

    private final MemorySpace space;
    private final int columns;
    private final int rows;
    private final Access[] kinds = Access.values();
    /** Reused per cell: the newest stamp of each kind. */
    private final int[] stamps = new int[kinds.length];

    private double zoom;
    private double originX;
    private double originY;
    /** The viewport the view was last laid out for; 0 until the first render. */
    private int viewWidth;
    private int viewHeight;
    private double fitZoom;

    HeatMapView(MemorySpace space) {
        this.space = space;
        this.columns = columnsFor(space.size());
        this.rows = (space.size() + columns - 1) / columns;
    }

    /** Rows of 32 for small tables; otherwise the power of two (at least 64) that's squarest. */
    static int columnsFor(int size) {
        if (size <= 0x400) {
            return 32;
        }
        int columns = 64;
        while ((long) columns * columns < size) {
            columns *= 2;
        }
        return columns;
    }

    MemorySpace space() {
        return space;
    }

    int columns() {
        return columns;
    }

    double zoom() {
        return zoom;
    }

    // ---- Drawing ------------------------------------------------------------------------------

    /**
     * Draws the view into the rectangle ({@code x}, {@code y}, {@code width}, {@code height}).
     *
     * @param now   the recorder's current frame (for fading)
     * @param hover the offset under the mouse, outlined, or -1
     */
    void render(Raster raster, int x, int y, int width, int height, int now, int hover) {
        if (width != viewWidth || height != viewHeight) {
            fit(width, height);
        }
        raster.fill(x, y, width, height, OUTSIDE);
        int[][] frames = new int[kinds.length][];
        for (int k = 0; k < kinds.length; k++) {
            frames[k] = space.lastFrames(kinds[k]);
        }
        if (zoom >= 1) {
            renderCells(raster, x, y, width, height, now, frames);
        } else {
            renderBlocks(raster, x, y, width, height, now, frames);
        }
        if (hover >= 0) {
            outlineCell(raster, x, y, hover);
        }
    }

    /**
     * Zoomed in: each cell is at least a pixel, so draw cell by cell. Edge cells can overhang
     * the viewport by part of a cell; the caller paints its bars after the view, over them.
     */
    private void renderCells(Raster raster, int x, int y, int width, int height, int now, int[][] frames) {
        int firstColumn = Math.max(0, (int) Math.floor(originX));
        int firstRow = Math.max(0, (int) Math.floor(originY));
        int endColumn = Math.min(columns, (int) Math.ceil(originX + width / zoom));
        int endRow = Math.min(rows, (int) Math.ceil(originY + height / zoom));
        int gap = zoom >= GRID_GAP_ZOOM ? 1 : 0;
        for (int row = firstRow; row < endRow; row++) {
            int top = y + (int) Math.floor((row - originY) * zoom);
            int bottom = y + (int) Math.floor((row + 1 - originY) * zoom);
            for (int column = firstColumn; column < endColumn; column++) {
                int offset = row * columns + column;
                if (offset >= space.size()) {
                    break;
                }
                int left = x + (int) Math.floor((column - originX) * zoom);
                int right = x + (int) Math.floor((column + 1 - originX) * zoom);
                for (int k = 0; k < frames.length; k++) {
                    stamps[k] = frames[k] == null ? 0 : frames[k][offset];
                }
                if (gap > 0) {
                    raster.fill(left, top, right - left, bottom - top, GRID);
                }
                raster.fill(left, top, right - left - gap, bottom - top - gap, HeatPalette.mix(stamps, now));
            }
        }
    }

    /**
     * Zoomed out: each pixel covers a block of cells. For every kind, take the newest stamp in
     * the block, so recent activity anywhere in it shows.
     *
     * <p>Speed matters here (a 4 MiB ROM is millions of stamps per kind), so the column range
     * of every pixel is worked out once, and each memory row is scanned once, left to right,
     * folding its stamps into a per-pixel accumulator for the current pixel row.
     */
    private void renderBlocks(Raster raster, int x, int y, int width, int height, int now, int[][] frames) {
        int size = space.size();
        int kindCount = frames.length;
        int[] columnStart = new int[width];
        int[] columnEnd = new int[width];
        for (int px = 0; px < width; px++) {
            int start = (int) Math.floor(originX + px / zoom);
            int end = Math.max(start + 1, (int) Math.floor(originX + (px + 1) / zoom));
            columnStart[px] = Math.max(0, start);
            columnEnd[px] = Math.min(columns, end);
        }
        int[] newest = new int[width * kindCount];
        boolean[] inside = new boolean[width];
        for (int py = 0; py < height; py++) {
            int rowStart = Math.max(0, (int) Math.floor(originY + py / zoom));
            int rowEnd = Math.min(rows, Math.max(rowStart + 1, (int) Math.floor(originY + (py + 1) / zoom)));
            if (rowStart >= rowEnd) {
                continue;
            }
            java.util.Arrays.fill(newest, 0);
            java.util.Arrays.fill(inside, false);
            for (int row = rowStart; row < rowEnd; row++) {
                int rowBase = row * columns;
                int rowLimit = Math.min(columns, size - rowBase); // the last row may be short
                for (int px = 0; px < width; px++) {
                    if (columnStart[px] < Math.min(columnEnd[px], rowLimit)) {
                        inside[px] = true;
                    }
                }
                for (int k = 0; k < kindCount; k++) {
                    int[] f = frames[k];
                    if (f == null) {
                        continue;
                    }
                    for (int px = 0; px < width; px++) {
                        int end = Math.min(columnEnd[px], rowLimit);
                        int best = newest[px * kindCount + k];
                        for (int column = columnStart[px]; column < end; column++) {
                            int stamp = f[rowBase + column];
                            if (stamp > best) {
                                best = stamp;
                            }
                        }
                        newest[px * kindCount + k] = best;
                    }
                }
            }
            for (int px = 0; px < width; px++) {
                if (inside[px]) {
                    System.arraycopy(newest, px * kindCount, stamps, 0, kindCount);
                    raster.set(x + px, y + py, HeatPalette.mix(stamps, now));
                }
            }
        }
    }

    /** A gold frame around the hovered cell, at least 5 pixels across so it's always visible. */
    private void outlineCell(Raster raster, int x, int y, int offset) {
        int column = offset % columns;
        int row = offset / columns;
        int left = x + (int) Math.floor((column - originX) * zoom);
        int top = y + (int) Math.floor((row - originY) * zoom);
        int size = Math.max(1, (int) Math.ceil(zoom));
        int pad = Math.max(0, (5 - size) / 2);
        raster.outline(left - pad - 1, top - pad - 1, size + 2 * pad + 2, size + 2 * pad + 2, 1, HOVER);
    }

    // ---- Interaction --------------------------------------------------------------------------

    /** Shows the whole space, centred, at the largest zoom that fits. */
    private void fit(int width, int height) {
        boolean first = viewWidth == 0;
        viewWidth = width;
        viewHeight = height;
        fitZoom = Math.min(width / (double) columns, height / (double) rows);
        if (first || zoom < fitZoom) {
            zoom = Math.min(fitZoom, MAX_ZOOM);
            originX = -(width / zoom - columns) / 2;
            originY = -(height / zoom - rows) / 2;
        }
        clampOrigin();
    }

    /** The byte offset under a point in the viewport (view-relative pixels), or -1. */
    int offsetAt(int px, int py) {
        if (viewWidth == 0 || px < 0 || py < 0 || px >= viewWidth || py >= viewHeight) {
            return -1;
        }
        int column = (int) Math.floor(originX + px / zoom);
        int row = (int) Math.floor(originY + py / zoom);
        if (column < 0 || row < 0 || column >= columns || row >= rows) {
            return -1;
        }
        int offset = row * columns + column;
        return offset < space.size() ? offset : -1;
    }

    /**
     * Zooms by {@code clicks} wheel steps (25% each; positive = in), keeping the point under
     * the cursor ({@code px}, {@code py}, view-relative) in place.
     */
    void zoomAt(double clicks, int px, int py) {
        double cellX = originX + px / zoom;
        double cellY = originY + py / zoom;
        double minimum = Math.min(fitZoom, 1.0);
        zoom = Math.max(minimum, Math.min(MAX_ZOOM, zoom * Math.pow(1.25, clicks)));
        originX = cellX - px / zoom;
        originY = cellY - py / zoom;
        clampOrigin();
    }

    /** Moves the view by a mouse drag of ({@code dx}, {@code dy}) pixels from {@code from}. */
    void panFrom(double[] from, int dx, int dy) {
        originX = from[0] - dx / zoom;
        originY = from[1] - dy / zoom;
        clampOrigin();
    }

    /** The current origin, to start a drag from. */
    double[] origin() {
        return new double[] {originX, originY};
    }

    /** Keeps at least a quarter of the viewport showing memory. */
    private void clampOrigin() {
        double visibleColumns = viewWidth / zoom;
        double visibleRows = viewHeight / zoom;
        originX = clamp(originX, -visibleColumns * 0.75, columns - visibleColumns * 0.25);
        originY = clamp(originY, -visibleRows * 0.75, rows - visibleRows * 0.25);
    }

    private static double clamp(double value, double low, double high) {
        return low > high ? (low + high) / 2 : Math.max(low, Math.min(high, value));
    }
}
