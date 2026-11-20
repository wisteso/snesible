package snesible.ui;

/**
 * A second screen shown next to the game, such as the visual memory debugger. It draws itself
 * into a plain ARGB pixel buffer and receives plain mouse and key events, so it needs no
 * toolkit: the OpenGL frontend shows it in a second GLFW window, the Swing frontend in a
 * second {@code JFrame}, and a headless tool can render it straight to a PNG.
 *
 * <p><b>Threading:</b> every method is called on the thread that runs the emulation (the main
 * thread in the OpenGL window, the {@code emulation} thread in Swing), between frames. The
 * Swing frontend queues its events and delivers them there. So an implementation can read
 * the console freely and needs no locks.
 *
 * <p><b>Debug support:</b> playing a game doesn't need this; it exists for
 * {@code snesible.debug.DebugMain}.
 */
public interface ToolScreen {

    /** The window title. */
    String title();

    /** The size to open the window at, in pixels. */
    int preferredWidth();

    int preferredHeight();

    /** Called once after every emulated frame, before {@link #render}. */
    void afterFrame();

    /**
     * Draws the screen into {@code argb} ({@code width x height}, row by row from the top,
     * {@code 0xAARRGGBB}; alpha is ignored).
     */
    void render(int[] argb, int width, int height);

    /** The mouse moved to ({@code x}, {@code y}), in the same pixels as {@link #render}. */
    void mouseMoved(int x, int y);

    /**
     * A mouse button went down or up.
     *
     * @param button 0 = left, 1 = right, 2 = middle
     */
    void mouseButton(int button, boolean pressed, int x, int y);

    /** The wheel turned; positive {@code clicks} = away from the user (zoom in). */
    void mouseWheel(double clicks, int x, int y);

    /** The mouse left the window. */
    void mouseExited();

    /** A character was typed (for entering values). */
    void keyTyped(char c);

    /** One of the editing and navigation keys was pressed. */
    void keyPressed(ToolKey key);

    /** The keys a tool screen gets, independent of the toolkit's key codes. */
    enum ToolKey { ENTER, ESCAPE, BACKSPACE, TAB, LEFT, RIGHT, UP, DOWN, PAGE_UP, PAGE_DOWN, HOME, END }
}
