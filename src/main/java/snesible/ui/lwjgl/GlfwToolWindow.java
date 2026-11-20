package snesible.ui.lwjgl;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import snesible.ui.ToolRedrawPacer;
import snesible.ui.ToolScreen;
import snesible.ui.ToolScreen.ToolKey;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

/**
 * A second GLFW window that shows a {@link ToolScreen} (the memory debugger) next to the game.
 *
 * <p><b>Threads and contexts:</b> like the game window it lives on the main thread, and its
 * events arrive through the same {@code glfwPollEvents()} call, so its callbacks run on the
 * emulation's thread as {@link ToolScreen} requires. It has its own OpenGL context: drawing
 * makes that current, draws, swaps, and makes the game's context current again (LWJGL needs
 * each context's capabilities set alongside it).
 *
 * <p><b>Drawing:</b> the tool screen renders into an ARGB buffer the size of the window's
 * framebuffer, which is uploaded as a texture (through a pixel buffer object; see
 * {@link #upload}) and drawn 1:1 with a pass-through shader (no NTSC filter, no scaling). It swaps without waiting for vsync (swap interval 0); otherwise
 * two windows each waiting for the display could halve the game's frame rate.
 *
 * <p><b>Showing and hiding:</b> F12 in the game window toggles it; its close button hides it
 * (the game keeps running); entering exclusive fullscreen hides it and leaving shows it again
 * (the user's choice: debug with borderless fullscreen, F11, instead).
 *
 * <p><b>Debug support:</b> used only by {@code snesible.debug.DebugMain}.
 */
final class GlfwToolWindow {

    private static final String VERTEX_SHADER = """
            #version 330 core
            // One triangle that covers the screen, from the vertex number alone (no vertex data).
            out vec2 uv;
            void main() {
                vec2 corner = vec2((gl_VertexID == 1) ? 3.0 : -1.0, (gl_VertexID == 2) ? 3.0 : -1.0);
                uv = vec2((corner.x + 1.0) * 0.5, 1.0 - (corner.y + 1.0) * 0.5); // row 0 at the top
                gl_Position = vec4(corner, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 330 core
            uniform sampler2D screen;
            in vec2 uv;
            out vec4 color;
            void main() {
                color = vec4(texture(screen, uv).rgb, 1.0);
            }
            """;

    private final ToolScreen screen;
    private final long gameWindow;
    private final GLCapabilities gameCapabilities;
    private final ToolRedrawPacer pacer = new ToolRedrawPacer();
    private long window;
    private GLCapabilities capabilities;
    private int program;
    private int vertexArray;
    private int texture;
    /** The texture's allocated size; storage is re-allocated only when it changes. */
    private int textureWidth;
    private int textureHeight;
    /** The pixel buffer object each frame is uploaded through (see {@link #upload}). */
    private int pixelBuffer;
    private int[] pixels = new int[0];

    /** Whether the user wants it shown (F12, close button). */
    private boolean wanted = true;
    /** Hidden while the game is in exclusive fullscreen. */
    private boolean suppressed;

    private GlfwToolWindow(ToolScreen screen, long gameWindow, GLCapabilities gameCapabilities) {
        this.screen = screen;
        this.gameWindow = gameWindow;
        this.gameCapabilities = gameCapabilities;
    }

    /**
     * Opens the window to the right of the game window, kept inside {@code monitor}'s work
     * area. Leaves the game's context current.
     */
    static GlfwToolWindow open(ToolScreen screen, long gameWindow, GLCapabilities gameCapabilities, long monitor) {
        GlfwToolWindow tool = new GlfwToolWindow(screen, gameWindow, gameCapabilities);
        tool.create(monitor);
        return tool;
    }

    private void create(long monitor) {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(screen.preferredWidth(), screen.preferredHeight(), screen.title(), NULL, NULL);
        if (window == NULL) {
            throw new IllegalStateException("couldn't create the tool window");
        }
        int[] x = new int[1];
        int[] y = new int[1];
        int[] width = new int[1];
        int[] height = new int[1];
        glfwGetWindowPos(gameWindow, x, y);
        glfwGetWindowSize(gameWindow, width, height);
        // Beside the game if it fits on the monitor, else as far right as it fits (overlapping).
        int[] areaX = new int[1];
        int[] areaY = new int[1];
        int[] areaWidth = new int[1];
        int[] areaHeight = new int[1];
        glfwGetMonitorWorkarea(monitor, areaX, areaY, areaWidth, areaHeight);
        int left = Math.max(areaX[0], Math.min(x[0] + width[0] + 16, areaX[0] + areaWidth[0] - screen.preferredWidth()));
        int top = Math.max(areaY[0] + 32, Math.min(y[0], areaY[0] + areaHeight[0] - screen.preferredHeight()));
        glfwSetWindowPos(window, left, top);

        glfwMakeContextCurrent(window);
        capabilities = GL.createCapabilities();
        glfwSwapInterval(0); // never wait for vsync here (see the class comment)
        program = buildProgram();
        vertexArray = glGenVertexArrays();
        texture = glGenTextures();
        pixelBuffer = glGenBuffers();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        useGameContext();

        glfwSetCursorPosCallback(window, (w, mx, my) -> {
            int[] p = toPixels(mx, my);
            screen.mouseMoved(p[0], p[1]);
        });
        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            if (button > 2) {
                return;
            }
            double[] mx = new double[1];
            double[] my = new double[1];
            glfwGetCursorPos(window, mx, my);
            int[] p = toPixels(mx[0], my[0]);
            screen.mouseButton(button, action == GLFW_PRESS, p[0], p[1]);
        });
        glfwSetScrollCallback(window, (w, dx, dy) -> {
            double[] mx = new double[1];
            double[] my = new double[1];
            glfwGetCursorPos(window, mx, my);
            int[] p = toPixels(mx[0], my[0]);
            screen.mouseWheel(dy, p[0], p[1]);
        });
        glfwSetCursorEnterCallback(window, (w, entered) -> {
            if (!entered) {
                screen.mouseExited();
            }
        });
        glfwSetCharCallback(window, (w, codepoint) -> {
            if (codepoint < 0x10000) {
                screen.keyTyped((char) codepoint);
            }
        });
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (action != GLFW_RELEASE) {
                ToolKey toolKey = toolKey(key);
                if (toolKey != null) {
                    screen.keyPressed(toolKey);
                }
            }
        });
        glfwSetWindowCloseCallback(window, w -> {
            glfwSetWindowShouldClose(window, false); // hide, don't close: F12 brings it back
            wanted = false;
            updateVisibility();
        });
        updateVisibility();
        glfwFocusWindow(gameWindow); // the game keeps the keyboard
    }

    /** Window coordinates (what the cursor callbacks give) to framebuffer pixels (what we draw). */
    private int[] toPixels(double x, double y) {
        int[] windowWidth = new int[1];
        int[] windowHeight = new int[1];
        int[] bufferWidth = new int[1];
        int[] bufferHeight = new int[1];
        glfwGetWindowSize(window, windowWidth, windowHeight);
        glfwGetFramebufferSize(window, bufferWidth, bufferHeight);
        double sx = windowWidth[0] == 0 ? 1 : bufferWidth[0] / (double) windowWidth[0];
        double sy = windowHeight[0] == 0 ? 1 : bufferHeight[0] / (double) windowHeight[0];
        return new int[] {(int) (x * sx), (int) (y * sy)};
    }

    private static ToolKey toolKey(int key) {
        return switch (key) {
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> ToolKey.ENTER;
            case GLFW_KEY_ESCAPE -> ToolKey.ESCAPE;
            case GLFW_KEY_BACKSPACE -> ToolKey.BACKSPACE;
            case GLFW_KEY_TAB -> ToolKey.TAB;
            case GLFW_KEY_LEFT -> ToolKey.LEFT;
            case GLFW_KEY_RIGHT -> ToolKey.RIGHT;
            case GLFW_KEY_UP -> ToolKey.UP;
            case GLFW_KEY_DOWN -> ToolKey.DOWN;
            case GLFW_KEY_PAGE_UP -> ToolKey.PAGE_UP;
            case GLFW_KEY_PAGE_DOWN -> ToolKey.PAGE_DOWN;
            case GLFW_KEY_HOME -> ToolKey.HOME;
            case GLFW_KEY_END -> ToolKey.END;
            default -> null;
        };
    }

    /**
     * After each emulated frame (main thread, game context current): lets the tool screen
     * update, then redraws it if it's visible and the pacer allows. Leaves the game's context
     * current.
     */
    void afterFrame() {
        screen.afterFrame();
        if (!visible() || glfwGetWindowAttrib(window, GLFW_ICONIFIED) == GLFW_TRUE || !pacer.shouldRedraw()) {
            return;
        }
        int[] width = new int[1];
        int[] height = new int[1];
        glfwGetFramebufferSize(window, width, height);
        if (width[0] <= 0 || height[0] <= 0) {
            return;
        }
        long started = System.nanoTime();
        if (pixels.length != width[0] * height[0]) {
            pixels = new int[width[0] * height[0]];
        }
        screen.render(pixels, width[0], height[0]);

        glfwMakeContextCurrent(window);
        GL.setCapabilities(capabilities);
        glViewport(0, 0, width[0], height[0]);
        upload(width[0], height[0]);
        glUseProgram(program);
        glBindVertexArray(vertexArray);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glfwSwapBuffers(window);
        useGameContext();
        // The whole redraw counts, not just render(): when maximized the upload used to cost
        // more than the drawing.
        pacer.redrawTook(System.nanoTime() - started);
    }

    /**
     * Copies {@link #pixels} into the texture through a pixel buffer object.
     *
     * <p><b>Why not just {@code glTexImage2D(..., pixels)}:</b> that was the cause of the game
     * slowing down with the debugger maximized. A maximized window on a 1440p screen is about
     * 3.5 million pixels, 14 MB a frame, and handing the driver a Java array makes the call
     * synchronous: LWJGL first copies the array to native memory, then the driver copies it
     * again (and converts it) before returning. Measured on an RTX 3070 Ti: about 8.4 ms per
     * upload, half of a 60 Hz frame, on the emulation thread.
     *
     * <p>With a pixel buffer object the driver hands out memory it can transfer from directly:
     * {@code glMapBufferRange} gives a pointer into it, one bulk copy fills it, and
     * {@code glTexSubImage2D} with an offset instead of an array only queues the transfer,
     * which the GPU does on its own time. Measured: about 0.7 ms.
     *
     * <p>Two details keep it from stalling. {@code glBufferData} with no data each time
     * "orphans" the buffer: if the GPU is still reading last frame's contents, the driver
     * gives us fresh memory instead of making us wait. And the texture's storage is allocated
     * only when the window's size changes; re-specifying it every frame (as
     * {@code glTexImage2D} does) would make the driver allocate new storage each time.
     */
    private void upload(int width, int height) {
        glBindTexture(GL_TEXTURE_2D, texture);
        if (width != textureWidth || height != textureHeight) {
            textureWidth = width;
            textureHeight = height;
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV,
                    (ByteBuffer) null); // storage only, no contents yet
        }
        long bytes = (long) width * height * Integer.BYTES;
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pixelBuffer);
        glBufferData(GL_PIXEL_UNPACK_BUFFER, bytes, GL_STREAM_DRAW); // orphan (see above)
        ByteBuffer mapped = glMapBufferRange(GL_PIXEL_UNPACK_BUFFER, 0, bytes,
                GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT);
        if (mapped != null) {
            mapped.order(ByteOrder.nativeOrder()).asIntBuffer().put(pixels, 0, width * height);
            // Unmapping can fail if the driver lost the memory (a display mode change); then
            // this frame's picture is simply skipped.
            if (glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER)) {
                // With a buffer bound, the last argument is an offset into it, not memory.
                // The ints are 0xAARRGGBB; read as little-endian bytes that's B, G, R, A, which
                // is what GL_BGRA with GL_UNSIGNED_INT_8_8_8_8_REV describes. The shader
                // ignores alpha.
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
            }
        }
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0); // later uploads elsewhere expect plain memory
    }

    /** F12: show or hide. */
    void toggle() {
        wanted = !wanted;
        updateVisibility();
    }

    /** Hides the window while the game is in exclusive fullscreen, shows it again after. */
    void setExclusiveFullscreen(boolean exclusive) {
        suppressed = exclusive;
        updateVisibility();
    }

    private boolean visible() {
        return wanted && !suppressed;
    }

    private void updateVisibility() {
        if (visible()) {
            glfwShowWindow(window);
        } else {
            glfwHideWindow(window);
        }
    }

    /** Destroys the window and its GL objects. Leaves the game's context current. */
    void destroy() {
        glfwMakeContextCurrent(window);
        GL.setCapabilities(capabilities);
        glDeleteTextures(texture);
        glDeleteBuffers(pixelBuffer);
        glDeleteVertexArrays(vertexArray);
        glDeleteProgram(program);
        useGameContext();
        org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
    }

    private void useGameContext() {
        glfwMakeContextCurrent(gameWindow);
        GL.setCapabilities(gameCapabilities);
    }

    private static int buildProgram() {
        int vertex = compile(GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compile(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("tool window shader didn't link: " + glGetProgramInfoLog(program));
        }
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        return program;
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("tool window shader didn't compile: " + glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
