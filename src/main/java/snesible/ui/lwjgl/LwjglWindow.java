package snesible.ui.lwjgl;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.glFinish;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import snesible.core.system.Snes;
import snesible.ui.EmulationSession;
import snesible.ui.Frame;
import snesible.ui.FrameClock;
import snesible.ui.InputSource;
import snesible.ui.PacingMode;
import snesible.ui.PlayOptions;
import snesible.ui.ToolScreen;
import snesible.ui.VideoSink;
import snesible.ui.input.GamepadMapping;
import snesible.ui.video.VideoMode;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.Configuration;

/**
 * The OpenGL window (GLFW through LWJGL): shows frames with {@link GlRenderer}, reads the
 * keyboard, and decides when each frame runs so that the picture moves smoothly.
 *
 * <h2>Everything on one thread</h2>
 * GLFW wants its window and events handled on the main thread, and the OpenGL context belongs
 * to the thread that created it. Emulating a frame takes only a few milliseconds, so the main
 * thread does everything in one loop: handle window events, run a frame
 * ({@link EmulationSession#runFrame()}, which calls {@link #present} with the picture), draw,
 * and show it ({@code glfwSwapBuffers}).
 *
 * <h2>Pacing</h2>
 * Vsync is always on: {@code glfwSwapBuffers} waits for the display's next refresh, so the
 * picture never tears. What differs is when frames run (see {@link PacingMode}):
 * <ul>
 *   <li><b>Locked to the display</b> when its refresh rate is within 0.5% of a multiple of the
 *       console's 60.10 Hz (60, 120, 240 Hz): one frame every N refreshes. The loop simply
 *       runs once per refresh, so the display is the clock.</li>
 *   <li><b>Steady clock</b> otherwise (144 Hz, say): a {@link FrameClock} at the console's
 *       rate, and each frame shown as soon as it's drawn. A G-Sync or FreeSync monitor then
 *       refreshes exactly when each frame arrives. Nothing here is specific to a vendor: the
 *       driver applies variable refresh to any window that presents below the monitor's
 *       maximum rate (in windowed mode only if the driver allows it; fullscreen always).</li>
 * </ul>
 * GLFW reports refresh rates as whole numbers (a 59.94 Hz display says 59), so the window
 * <i>measures</i> the real refresh interval by timing a few swaps. That also catches vsync
 * being switched off in the driver's settings, in which case the steady clock takes over. The
 * measurement is repeated when the window moves to another monitor or enters or leaves
 * exclusive fullscreen.
 *
 * <p>Keys: the controller keys (see {@link GlfwKeyboard}), F2 = next picture mode,
 * F3 = scanlines, F11 = borderless fullscreen, Alt+Enter = exclusive fullscreen,
 * F12 = show or hide the tool window (the memory debugger, when there is one), Escape = quit. Gamepads come and go by themselves ({@link GlfwGamepads}).
 */
public final class LwjglWindow implements VideoSink {

    private static final int SCALE = 3;
    /** Swaps timed to measure the display's refresh interval (the first few are ignored). */
    private static final int MEASURE_SWAPS = 60;
    /**
     * Right after a window appears, or a fullscreen switch, the first swaps can return without
     * waiting (the window isn't on screen yet, the driver's queue of frames isn't full), so the
     * first ones aren't counted.
     */
    private static final int MEASURE_SKIP = 20;
    /** If vsync seems not to work, measure again this much later, up to this many times. */
    private static final long RECHECK_DELAY_NANOS = 2_000_000_000L;
    private static final int MAX_RECHECKS = 3;
    /**
     * How far the borderless fullscreen window reaches past each edge of the monitor, in
     * pixels (see {@link #toggleFullscreen}). The picture is drawn inside that margin.
     */
    private static final int BORDERLESS_OVERHANG = 1;

    private final String title;
    private final PlayOptions options;
    private final GlfwKeyboard keyboard = new GlfwKeyboard();
    private long window;
    /** The game context's OpenGL functions (LWJGL keeps them per context and thread). */
    private GLCapabilities capabilities;
    private GlRenderer renderer;
    /** The memory debugger's window, or null when playing normally. */
    private GlfwToolWindow tool;
    /** The game being played, for F10 (the sound preset); null until {@link #play} starts. */
    private EmulationSession session;

    // Picture mode, changed with F2/F3.
    private VideoMode mode;
    private boolean scanlines;

    // Pacing: VSYNC (one frame every refreshesPerFrame swaps) or CLOCK.
    private PacingMode pacing = PacingMode.CLOCK;
    private int refreshesPerFrame = 1;
    private int refreshCounter;
    private FrameClock clock;
    private double measuredRefresh;
    private boolean pacingNeedsCheck = true;
    private long recheckAt;   // System.nanoTime() of a scheduled re-measurement, or 0
    private int recheckCount;
    private long monitorAtLastCheck = NULL;

    /** How the window is shown: in a window, or one of the two kinds of fullscreen. */
    private enum Fullscreen { OFF, BORDERLESS, EXCLUSIVE }

    private Fullscreen fullscreen = Fullscreen.OFF;
    // The windowed position and size, restored when leaving fullscreen.
    private final int[] windowedX = new int[1];
    private final int[] windowedY = new int[1];
    private final int[] windowedWidth = new int[1];
    private final int[] windowedHeight = new int[1];

    private LwjglWindow(String title, PlayOptions options) {
        this.title = title;
        this.options = options;
        this.mode = options.videoMode();
        this.scanlines = options.scanlines();
    }

    /**
     * Opens the window and its OpenGL 3.3 context, without starting anything yet. Throws if
     * that isn't possible (no OpenGL 3.3, LWJGL missing from the class path), so the caller can
     * fall back to the Swing window.
     */
    public static LwjglWindow open(String title, PlayOptions options) {
        useFfmMemoryAccess();
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("GLFW couldn't start");
        }
        LwjglWindow w = new LwjglWindow(title, options);
        try {
            w.create();
        } catch (RuntimeException e) {
            glfwTerminate();
            throw e;
        }
        return w;
    }

    /**
     * Plays the game until the window closes, then writes the save and closes the window.
     * Controller 1 is the keyboard plus the first gamepad; controller 2 the second gamepad.
     *
     * @param saveFile where battery-backed SRAM is kept, or null
     * @param mapping  button mapping for gamepads that aren't in GLFW's gamepad database
     */
    public void play(Snes snes, Path saveFile, GamepadMapping mapping) {
        play(snes, saveFile, mapping, null);
    }

    /**
     * Plays the game as {@link #play(Snes, Path, GamepadMapping)}, with a tool screen (the
     * memory debugger) in a second window beside it, updated after every frame.
     *
     * @param toolScreen the screen for the second window, or null for none
     */
    public void play(Snes snes, Path saveFile, GamepadMapping mapping, ToolScreen toolScreen) {
        GlfwGamepads gamepads = new GlfwGamepads(mapping);
        List<InputSource> inputs = List.of(keyboard, gamepads);
        EmulationSession session = new EmulationSession(snes, saveFile, this, inputs);
        session.setPrintStats(options.stats());
        session.setAudioSettings(options.audio());
        this.session = session;
        if (toolScreen != null) {
            tool = GlfwToolWindow.open(toolScreen, window, capabilities, currentMonitor());
            session.setAfterFrame(tool::afterFrame);
        }
        try {
            choosePacing(session.framesPerSecond()); // measure the display before the sound starts
            session.powerOn();
            loop(session);
            session.powerOff();
        } finally {
            gamepads.close();
            if (tool != null) {
                tool.destroy();
            }
            renderer.delete();
            Callbacks.glfwFreeCallbacks(window);
            glfwDestroyWindow(window);
            glfwTerminate();
            GLFWErrorCallback previous = glfwSetErrorCallback(null);
            if (previous != null) {
                previous.free();
            }
        }
    }

    /**
     * Makes LWJGL access native memory through Java's FFM API (as Snesible's own winmm code does)
     * instead of {@code sun.misc.Unsafe}. LWJGL 3.4 uses FFM for native calls on JDK 25, but
     * for memory it still prefers Unsafe while the JVM allows it, which makes the JVM print
     * "terminally deprecated" warnings. Must run before LWJGL touches native memory.
     */
    static void useFfmMemoryAccess() {
        Configuration.MEMORY_BACKEND.set("ffm");
    }

    private void create() {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        // OpenGL 3.3 "core": the modern API without the old fixed-function pipeline. Forward
        // compatibility is required for core contexts on macOS.
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(256 * SCALE, 224 * SCALE, "Snesible - " + title, NULL, NULL);
        if (window == NULL) {
            throw new IllegalStateException("couldn't create an OpenGL 3.3 window");
        }
        centerOn(glfwGetPrimaryMonitor());

        glfwMakeContextCurrent(window);
        capabilities = GL.createCapabilities();
        glfwSwapInterval(1); // vsync: each swap waits for the display's next refresh
        renderer = new GlRenderer();

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> handleKey(key, action, mods));
        glfwSetWindowFocusCallback(window, (win, focused) -> {
            if (!focused) {
                keyboard.releaseAll(); // releases while unfocused would never arrive
            }
        });
        glfwSetWindowPosCallback(window, (win, x, y) -> {
            if (currentMonitor() != monitorAtLastCheck) {
                pacingNeedsCheck = true; // moved to another monitor: its refresh rate may differ
            }
        });
        glfwShowWindow(window);
    }

    // =============================================================================================
    // The main loop
    // =============================================================================================

    private void loop(EmulationSession session) {
        double fps = session.framesPerSecond();
        while (!glfwWindowShouldClose(window)) {
            if (recheckAt != 0 && System.nanoTime() - recheckAt >= 0) {
                recheckAt = 0;
                pacingNeedsCheck = true;
            }
            if (pacingNeedsCheck) {
                choosePacing(fps);
            }
            boolean minimized = glfwGetWindowAttrib(window, GLFW_ICONIFIED) == GLFW_TRUE;
            if (pacing == PacingMode.CLOCK || minimized) {
                // A minimized window may not wait for vsync, so it always uses the clock.
                if (clock == null) {
                    clock = new FrameClock(fps);
                }
                clock.waitForNextFrame();
                glfwPollEvents();
                session.runFrame();
                if (!minimized) {
                    drawAndSwap();
                }
            } else {
                // Locked: the swap waits for each refresh; run a frame every refreshesPerFrame.
                glfwPollEvents();
                if (refreshCounter == 0) {
                    session.runFrame();
                }
                refreshCounter = (refreshCounter + 1) % refreshesPerFrame;
                drawAndSwap();
            }
        }
    }

    private void drawAndSwap() {
        int[] width = new int[1];
        int[] height = new int[1];
        glfwGetFramebufferSize(window, width, height);
        // Borderless fullscreen reaches one pixel past each edge of the screen; keep the
        // picture inside what's visible.
        int margin = fullscreen == Fullscreen.BORDERLESS ? BORDERLESS_OVERHANG : 0;
        renderer.draw(0, width[0], height[0], margin, scanlines);
        glfwSwapBuffers(window);
    }

    /** Measures the display's refresh rate and picks the pacing mode. */
    private void choosePacing(double fps) {
        pacingNeedsCheck = false;
        monitorAtLastCheck = currentMonitor();
        measuredRefresh = measureRefreshRate();
        PacingDecision decision = PacingDecision.choose(measuredRefresh, fps, options.pacing());
        if (decision.refusedVsync()) {
            System.out.printf("Can't lock to vsync: %.2f Hz isn't a multiple of %.2f fps.%n", measuredRefresh, fps);
        }
        PacingMode chosen = decision.mode();
        boolean vsyncWorks = decision.vsyncWorks();
        pacing = chosen;
        refreshesPerFrame = decision.refreshesPerFrame();
        refreshCounter = 0;
        clock = null; // a fresh timeline when the clock is next needed

        if (!vsyncWorks && recheckCount < MAX_RECHECKS) {
            recheckCount++;
            recheckAt = System.nanoTime() + RECHECK_DELAY_NANOS; // perhaps it was just too early
        } else if (vsyncWorks) {
            recheckCount = 0;
        }
        String display = vsyncWorks
                ? String.format("%.2f Hz", measuredRefresh)
                : String.format("swaps came %.0f times a second, so vsync isn't taking effect"
                        + " (is it forced off in the graphics driver?)%s", measuredRefresh,
                        recheckAt != 0 ? "; measuring again shortly" : "");
        if (chosen == PacingMode.VSYNC) {
            System.out.printf("Display: %s. Pacing: locked to vsync, one frame every %d refresh%s (%.2f fps).%n",
                    display, refreshesPerFrame, refreshesPerFrame == 1 ? "" : "es", measuredRefresh / refreshesPerFrame);
        } else {
            System.out.printf("Display: %s. Pacing: steady clock at %.2f fps"
                    + " (smooth on G-Sync/FreeSync displays; a slight judder on fixed-rate ones).%n", display, fps);
        }
    }

    /**
     * Times a series of swaps with vsync on and returns the median rate in Hz. Draws the
     * current picture meanwhile, so the window doesn't flash.
     */
    private double measureRefreshRate() {
        long[] intervals = new long[MEASURE_SWAPS - MEASURE_SKIP];
        long previous = System.nanoTime();
        for (int i = 0; i < MEASURE_SWAPS; i++) {
            glfwPollEvents();
            drawAndSwap();
            glFinish(); // wait until the swap has really happened, not just been queued
            long now = System.nanoTime();
            if (i >= MEASURE_SKIP) {
                intervals[i - MEASURE_SKIP] = now - previous;
            }
            previous = now;
        }
        Arrays.sort(intervals);
        long median = intervals[intervals.length / 2];
        return median <= 0 ? Double.POSITIVE_INFINITY : 1e9 / median;
    }

    // =============================================================================================
    // VideoSink
    // =============================================================================================

    /** Uploads the frame (and, in an NTSC mode, runs the NTSC shader). Called on the main thread. */
    @Override
    public void present(Frame frame) {
        // Scanlines are drawn as beams when the picture is scaled to the window (GlRenderer.draw),
        // not with the NTSC filter's own darken-every-second-row option.
        renderer.upload(frame, mode, false);
    }

    @Override
    public void showFramesPerSecond(int fps) {
        String how = pacing == PacingMode.VSYNC ? "vsync" : "clock";
        glfwSetWindowTitle(window, "Snesible - " + title + " - " + fps + " fps (" + how + ")");
    }

    // =============================================================================================
    // Keys, fullscreen, monitors
    // =============================================================================================

    private void handleKey(int key, int action, int mods) {
        if (action == GLFW_PRESS && key == GLFW_KEY_ENTER && (mods & GLFW_MOD_ALT) != 0) {
            // Alt+Enter, not Start: Enter is a controller key, so check this before passing
            // keys on. (Enter's release still reaches the keyboard, which is harmless.)
            toggleFullscreen(Fullscreen.EXCLUSIVE);
            return;
        }
        if (action == GLFW_PRESS) {
            switch (key) {
                case GLFW_KEY_ESCAPE -> {
                    glfwSetWindowShouldClose(window, true);
                    return;
                }
                case GLFW_KEY_F2 -> {
                    mode = mode.next();
                    System.out.println("Picture: " + mode.label());
                    return;
                }
                case GLFW_KEY_F3 -> {
                    scanlines = !scanlines;
                    System.out.println("Scanlines " + (scanlines ? "on" : "off"));
                    return;
                }
                case GLFW_KEY_F9 -> {
                    System.out.println("This window notices gamepads by itself; no need to search.");
                    return;
                }
                case GLFW_KEY_F10 -> {
                    if (session != null) {
                        session.cycleAudioPreset();
                    }
                    return;
                }
                case GLFW_KEY_F11 -> {
                    toggleFullscreen(Fullscreen.BORDERLESS);
                    return;
                }
                case GLFW_KEY_F12 -> {
                    if (tool != null) {
                        tool.toggle();
                    }
                    return;
                }
                default -> {
                }
            }
        }
        keyboard.handle(key, action);
    }

    /**
     * Switches between a window and fullscreen on the monitor the window is on. In fullscreen,
     * either key goes back to the window. There are two kinds:
     *
     * <p><b>Borderless</b> (F11, quick): a window without a frame, covering the monitor and
     * one pixel more on every side. The switch is instant. Why the extra pixel: when a window
     * covers a monitor <i>exactly</i>, Windows hands it the display directly ("independent
     * flip", the same as exclusive fullscreen), and that change of presentation path makes a
     * G-Sync monitor re-sync, a blank screen for about 4 s on the user's display. A window that
     * doesn't fit the monitor exactly stays with the desktop compositor, so nothing changes for
     * the monitor. It must be larger, not smaller: Windows hides the taskbar only for a
     * foreground window that covers the whole monitor (one pixel short, the taskbar stayed in
     * front; checked with screen captures). The cost: the compositor is in the way, so
     * G-Sync/FreeSync works here only if the driver allows it for windows (NVIDIA: "Enable
     * G-SYNC for windowed and full screen mode"); otherwise the steady clock judders slightly
     * on a 144 Hz display. The compositor probably also adds up to one refresh of latency
     * (~7 ms at 144 Hz; not measured).
     *
     * <p><b>Exclusive</b> (Alt+Enter, lowest latency): GLFW's real fullscreen, at the monitor's
     * resolution and, if {@code --refresh} was given, that refresh rate (on a 144 Hz display
     * without G-Sync/FreeSync, 120 Hz gives perfectly even frames). Variable refresh works with
     * the drivers' default settings, but each switch blanks the screen while the monitor
     * re-syncs (measured: swaps stall for up to ~2.3 s after the switch; the blank looks longer).
     *
     * @param kind the kind of fullscreen to enter, if the window isn't fullscreen already
     */
    private void toggleFullscreen(Fullscreen kind) {
        Fullscreen before = fullscreen;
        if (fullscreen == Fullscreen.OFF) {
            glfwGetWindowPos(window, windowedX, windowedY);
            glfwGetWindowSize(window, windowedWidth, windowedHeight);
            long monitor = currentMonitor();
            GLFWVidMode desktop = glfwGetVideoMode(monitor);
            if (kind == Fullscreen.EXCLUSIVE) {
                int refresh = options.fullscreenRefresh() > 0 ? options.fullscreenRefresh() : desktop.refreshRate();
                glfwSetWindowMonitor(window, monitor, 0, 0, desktop.width(), desktop.height(), refresh);
            } else {
                int[] mx = new int[1];
                int[] my = new int[1];
                glfwGetMonitorPos(monitor, mx, my);
                glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_FALSE);
                // Past the monitor's edges on every side (see above).
                glfwSetWindowMonitor(window, NULL, mx[0] - BORDERLESS_OVERHANG, my[0] - BORDERLESS_OVERHANG,
                        desktop.width() + 2 * BORDERLESS_OVERHANG, desktop.height() + 2 * BORDERLESS_OVERHANG,
                        GLFW_DONT_CARE);
            }
            fullscreen = kind;
        } else {
            glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_TRUE);
            glfwSetWindowMonitor(window, NULL, windowedX[0], windowedY[0],
                    windowedWidth[0], windowedHeight[0], GLFW_DONT_CARE);
            fullscreen = Fullscreen.OFF;
        }
        glfwSwapInterval(1);
        if (tool != null) {
            // The debugger stays out of exclusive fullscreen (use borderless, F11, with it).
            tool.setExclusiveFullscreen(fullscreen == Fullscreen.EXCLUSIVE);
        }
        if (before == Fullscreen.EXCLUSIVE || fullscreen == Fullscreen.EXCLUSIVE) {
            pacingNeedsCheck = true; // the refresh rate may have changed
        }
        // Borderless stays on the same monitor at the same refresh rate, so there's nothing to
        // re-measure (which would pause the game for ~0.4 s).
    }

    /** The monitor showing the window: its fullscreen monitor, or the one under its center. */
    private long currentMonitor() {
        long fullscreen = glfwGetWindowMonitor(window);
        if (fullscreen != NULL) {
            return fullscreen;
        }
        int[] x = new int[1];
        int[] y = new int[1];
        int[] w = new int[1];
        int[] h = new int[1];
        glfwGetWindowPos(window, x, y);
        glfwGetWindowSize(window, w, h);
        int centerX = x[0] + w[0] / 2;
        int centerY = y[0] + h[0] / 2;
        PointerBuffer monitors = glfwGetMonitors();
        if (monitors != null) {
            for (int i = 0; i < monitors.limit(); i++) {
                long monitor = monitors.get(i);
                int[] mx = new int[1];
                int[] my = new int[1];
                glfwGetMonitorPos(monitor, mx, my);
                GLFWVidMode vm = glfwGetVideoMode(monitor);
                if (vm != null && centerX >= mx[0] && centerX < mx[0] + vm.width()
                        && centerY >= my[0] && centerY < my[0] + vm.height()) {
                    return monitor;
                }
            }
        }
        return glfwGetPrimaryMonitor();
    }

    private void centerOn(long monitor) {
        GLFWVidMode vm = glfwGetVideoMode(monitor);
        if (vm == null) {
            return;
        }
        int[] mx = new int[1];
        int[] my = new int[1];
        glfwGetMonitorPos(monitor, mx, my);
        glfwSetWindowPos(window, mx[0] + (vm.width() - 256 * SCALE) / 2, my[0] + (vm.height() - 224 * SCALE) / 2);
    }
}
