package jfc.ui.debug;

import java.util.ArrayList;
import java.util.List;
import jfc.debug.Access;
import jfc.debug.AccessRecorder;
import jfc.debug.MemoryObserver;
import jfc.debug.MemorySpace;
import jfc.ui.ToolScreen;

/**
 * The visual memory debugger's window contents (Milestone 9a: the heat map).
 * <pre>
 *   +--------------------------------------------------------------+
 *   | [WRAM 128K] [ROM 2M] [Cart RAM 8K] [VRAM 64K] ... (tabs)     |
 *   +--------------------------------------------------------------+
 *   |                                                              |
 *   |        the selected memory as a heat map (HeatMapView)       |
 *   |                                                              |
 *   +--------------------------------------------------------------+
 *   | VRAM $3A41 = $7E   read -  write 12  ...   (the hovered byte) |
 *   | legend: read write execute DMA PPU/DSP           frame 1234   |
 *   +--------------------------------------------------------------+
 * </pre>
 * Mouse: the wheel zooms (around the cursor), the left button drags the view and picks tabs.
 * The right button is reserved for editing values (Milestone 9b). Keys 1-9 pick a tab too.
 *
 * <p>Everything here runs on the emulation thread (see {@link ToolScreen}), so it reads the
 * recorder and the console directly.
 */
public final class DebugScreen implements ToolScreen {

    private static final int BAR = 0x202028;
    private static final int TAB = 0x30303C;
    private static final int TAB_SELECTED = 0x50507A;
    private static final int TEXT = 0xE0E0E0;
    private static final int DIM_TEXT = 0x909098;

    private final MemoryObserver observer;
    private final String gameTitle;
    private final List<HeatMapView> views = new ArrayList<>();
    private final Raster raster = new Raster();
    private int selected;

    // Layout from the last render, for hit-testing the mouse.
    private int scale = 1;
    private int barHeight;
    private int viewTop;
    private int viewHeight;
    private int width;
    private final List<int[]> tabBounds = new ArrayList<>();

    // Mouse state.
    private int mouseX = -1;
    private int mouseY = -1;
    private boolean dragging;
    private int dragStartX;
    private int dragStartY;
    private double[] dragOrigin;

    /** @param observer an attached observer (its recorder has the spaces to show) */
    public DebugScreen(MemoryObserver observer, String gameTitle) {
        this.observer = observer;
        this.gameTitle = gameTitle;
        for (MemorySpace space : observer.recorder().spaces()) {
            views.add(new HeatMapView(space));
        }
    }

    @Override
    public String title() {
        return "JFC memory debugger - " + gameTitle;
    }

    @Override
    public int preferredWidth() {
        return 1024;
    }

    @Override
    public int preferredHeight() {
        return 800;
    }

    @Override
    public void afterFrame() {
        observer.endFrame();
    }

    /** Selects a tab by memory name (as in {@link MemorySpace#name()}, ignoring case). */
    public boolean select(String spaceName) {
        for (int i = 0; i < views.size(); i++) {
            if (views.get(i).space().name().equalsIgnoreCase(spaceName)) {
                selected = i;
                return true;
            }
        }
        return false;
    }

    /** The names of the memories, in tab order. */
    public List<String> spaceNames() {
        return views.stream().map(v -> v.space().name()).toList();
    }

    // ---- Drawing ------------------------------------------------------------------------------

    @Override
    public void render(int[] argb, int width, int height) {
        this.width = width;
        raster.target(argb, width, height);
        scale = height >= 700 ? 2 : 1;
        int line = PixelFont.CELL_HEIGHT * scale;
        barHeight = line + 4 * scale;
        int statusHeight = 2 * line + 4 * scale;
        viewTop = barHeight;
        viewHeight = Math.max(1, height - barHeight - statusHeight);

        AccessRecorder recorder = observer.recorder();
        HeatMapView view = views.get(selected);
        int hover = hoveredOffset();
        view.render(raster, 0, viewTop, width, viewHeight, recorder.frame(), hover);

        drawTabs();
        drawStatus(viewTop + viewHeight, statusHeight, view, hover, recorder.frame());
    }

    private void drawTabs() {
        raster.fill(0, 0, width, barHeight, BAR);
        tabBounds.clear();
        int x = 2 * scale;
        for (int i = 0; i < views.size(); i++) {
            MemorySpace space = views.get(i).space();
            String label = space.name() + " " + sizeLabel(space.size());
            int tabWidth = Raster.textWidth(label, scale) + 6 * scale;
            raster.fill(x, scale, tabWidth, barHeight - 2 * scale, i == selected ? TAB_SELECTED : TAB);
            raster.text(label, x + 3 * scale, 3 * scale, scale, i == selected ? TEXT : DIM_TEXT);
            tabBounds.add(new int[] {x, x + tabWidth});
            x += tabWidth + 2 * scale;
        }
    }

    private void drawStatus(int top, int height, HeatMapView view, int hover, int frame) {
        raster.fill(0, top, width, height, BAR);
        int line = PixelFont.CELL_HEIGHT * scale;
        int y = top + 2 * scale;
        int x = 3 * scale;
        raster.text(hoverText(view.space(), hover, frame), x, y, scale, TEXT);

        y += line + scale;
        for (Access kind : Access.values()) {
            int box = 7 * scale;
            raster.fill(x, y, box, box, HeatPalette.color(kind));
            x = raster.text(kind.label(), x + box + 3 * scale, y, scale, DIM_TEXT) + 8 * scale;
        }
        String right = String.format("zoom %s  frame %d", zoomLabel(view.zoom()), frame);
        raster.text(right, width - Raster.textWidth(right, scale) - 3 * scale, y, scale, DIM_TEXT);
    }

    /**
     * The hovered byte: where it is, its value, and how many frames ago each kind of access
     * last happened ("-" = never).
     */
    private static String hoverText(MemorySpace space, int offset, int frame) {
        if (offset < 0) {
            return space.name() + ": point at a byte. Wheel: zoom. Drag: move.";
        }
        int digits = Math.max(4, Integer.toHexString(space.size() - 1).length());
        StringBuilder text = new StringBuilder(String.format("%s $%0" + digits + "X = $%02X  ",
                space.name(), offset, space.peek(offset)));
        for (Access kind : Access.values()) {
            int stamp = space.lastFrame(offset, kind);
            String age = stamp == 0 ? "-" : String.valueOf(frame - stamp);
            text.append(' ').append(kind == Access.CHIP ? "PPU/DSP" : kind.label()).append(' ').append(age);
        }
        return text.toString();
    }

    /** 131072 -> "128K", 2097152 -> "2M", 544 -> "544". */
    static String sizeLabel(int size) {
        if (size % (1 << 20) == 0) {
            return (size >> 20) + "M";
        }
        if (size % 1024 == 0) {
            return (size >> 10) + "K";
        }
        return String.valueOf(size);
    }

    private static String zoomLabel(double zoom) {
        return zoom >= 1 ? String.format("%.1fx", zoom) : String.format("1/%.1f", 1 / zoom);
    }

    /** The offset under the mouse in the selected view, or -1. */
    private int hoveredOffset() {
        if (mouseY < viewTop || mouseY >= viewTop + viewHeight) {
            return -1;
        }
        return views.get(selected).offsetAt(mouseX, mouseY - viewTop);
    }

    // ---- Input --------------------------------------------------------------------------------

    @Override
    public void mouseMoved(int x, int y) {
        mouseX = x;
        mouseY = y;
        if (dragging) {
            views.get(selected).panFrom(dragOrigin, x - dragStartX, y - dragStartY);
        }
    }

    @Override
    public void mouseButton(int button, boolean pressed, int x, int y) {
        mouseMoved(x, y);
        if (button != 0) {
            return; // right button: editing, Milestone 9b
        }
        if (!pressed) {
            dragging = false;
            return;
        }
        if (y < barHeight) {
            for (int i = 0; i < tabBounds.size(); i++) {
                if (x >= tabBounds.get(i)[0] && x < tabBounds.get(i)[1]) {
                    selected = i;
                }
            }
        } else if (y >= viewTop && y < viewTop + viewHeight) {
            dragging = true;
            dragStartX = x;
            dragStartY = y;
            dragOrigin = views.get(selected).origin();
        }
    }

    @Override
    public void mouseWheel(double clicks, int x, int y) {
        if (y >= viewTop && y < viewTop + viewHeight) {
            views.get(selected).zoomAt(clicks, x, y - viewTop);
        }
    }

    @Override
    public void mouseExited() {
        mouseX = -1;
        mouseY = -1;
        dragging = false;
    }

    @Override
    public void keyTyped(char c) {
        if (c >= '1' && c <= '9' && c - '1' < views.size()) {
            selected = c - '1';
        }
    }

    @Override
    public void keyPressed(ToolKey key) {
        // Editing keys arrive here in Milestone 9b.
    }
}
