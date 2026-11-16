package jfc.ui.lwjgl;

import static org.lwjgl.opengl.GL33C.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import jfc.ui.Frame;
import jfc.ui.video.VideoMode;
import jfc.ui.video.ntsc.SnesNtsc;

/**
 * Draws frames with OpenGL 3.3: owns the textures, the shader programs and the drawing.
 *
 * <p>Every frame's raw pixels (the PPU's master brightness and 15-bit color packed in an int)
 * are uploaded unchanged into an integer texture ({@code GL_R32UI}). What happens next depends
 * on the picture mode:
 * <ul>
 *   <li><b>Sharp pixels</b>: {@code snes-pixels.frag} turns each raw pixel into RGB while
 *       drawing to the window. The CPU does no conversion at all.</li>
 *   <li><b>NTSC modes</b>: two passes. First {@code snes-ntsc.frag} runs Blargg's NTSC filter,
 *       one fragment per output pixel, into an offscreen 602 x 448 texture (a framebuffer
 *       object), once per frame. Then {@code rgb-picture.frag} scales that picture to the
 *       window with bilinear filtering. The filter's table of precomputed color smears is built
 *       by {@link SnesNtsc} in Java when the mode changes and uploaded as a texture; the
 *       per-frame work is all on the GPU.</li>
 * </ul>
 *
 * <p>The picture is drawn into a <i>viewport</i>: the largest rectangle with the picture's
 * shape that fits the window, centered, with black bars around it.
 *
 * <p>All methods must be called on the thread that owns the OpenGL context (the main thread).
 */
final class GlRenderer {

    private static final String SHADER_DIRECTORY = "/jfc/ui/lwjgl/shaders/";
    /** The NTSC table texture: 128 columns (3 burst phases x 42) by 8192 colors. */
    private static final int NTSC_TABLE_WIDTH = 128;
    private static final int NTSC_TABLE_HEIGHT = 8192;

    private final int sharpProgram;
    private final int ntscProgram;
    private final int presentProgram;
    /** OpenGL 3.3 core needs a vertex array object bound to draw, even with no vertex data. */
    private final int emptyVertexArray;
    private final int rawTexture;
    private final int ntscTableTexture;
    private final int ntscPictureTexture;
    private final int ntscFramebuffer;

    // Texture sizes as allocated; reallocated when a picture of another size arrives.
    private int rawWidth;
    private int rawHeight;
    private int ntscWidth;
    private int ntscHeight;

    /** The mode whose NTSC table is in the table texture, or null. */
    private VideoMode ntscTableMode;

    // What the next draw shows.
    private boolean showingNtsc;
    private int pictureWidth = 256;
    private int pictureHeight = 224;

    GlRenderer() {
        int vertex = compile(GL_VERTEX_SHADER, "fullscreen.vert");
        sharpProgram = link(vertex, compile(GL_FRAGMENT_SHADER, "snes-pixels.frag"));
        ntscProgram = link(vertex, compile(GL_FRAGMENT_SHADER, "snes-ntsc.frag"));
        presentProgram = link(vertex, compile(GL_FRAGMENT_SHADER, "rgb-picture.frag"));
        glDeleteShader(vertex);
        emptyVertexArray = glGenVertexArrays();

        rawTexture = createTexture(GL_NEAREST);        // integer textures can't be filtered
        ntscTableTexture = createTexture(GL_NEAREST);
        ntscPictureTexture = createTexture(GL_LINEAR); // scaled smoothly to the window
        ntscFramebuffer = glGenFramebuffers();
    }

    /**
     * Takes a new frame: uploads its raw pixels and, in an NTSC mode, runs the NTSC pass. The
     * frame's array is only read during this call.
     *
     * @param scanlines with an NTSC mode, darken every second output row (Snes9x's option)
     */
    void upload(Frame frame, VideoMode mode, boolean scanlines) {
        glBindTexture(GL_TEXTURE_2D, rawTexture);
        if (rawWidth != frame.width() || rawHeight != frame.height()) {
            rawWidth = frame.width();
            rawHeight = frame.height();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R32UI, rawWidth, rawHeight, 0,
                    GL_RED_INTEGER, GL_UNSIGNED_INT, (ByteBuffer) null);
        }
        // One 32-bit value per texel, read straight from the PPU's frame buffer.
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, frame.width(), frame.height(),
                GL_RED_INTEGER, GL_UNSIGNED_INT, frame.pixels());

        if (mode.ntscSetup() == null) {
            showingNtsc = false;
            pictureWidth = frame.width();
            pictureHeight = frame.height();
        } else {
            runNtscPass(frame, mode, scanlines);
            showingNtsc = true;
            pictureWidth = ntscWidth;
            pictureHeight = ntscHeight;
        }
    }

    /** Runs the NTSC filter on the uploaded frame into the offscreen picture texture. */
    private void runNtscPass(Frame frame, VideoMode mode, boolean scanlines) {
        if (ntscTableMode != mode) {
            // Simulating every color's smear takes tens of milliseconds, so only on a mode change.
            int[] table = new SnesNtsc(mode.ntscSetup()).table();
            glBindTexture(GL_TEXTURE_2D, ntscTableTexture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R32UI, NTSC_TABLE_WIDTH, NTSC_TABLE_HEIGHT, 0,
                    GL_RED_INTEGER, GL_UNSIGNED_INT, table);
            ntscTableMode = mode;
        }
        int width = SnesNtsc.outWidth(frame.width());
        int height = frame.height() * 2; // every line twice, as Snes9x draws it
        if (ntscWidth != width || ntscHeight != height) {
            ntscWidth = width;
            ntscHeight = height;
            glBindTexture(GL_TEXTURE_2D, ntscPictureTexture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, ntscFramebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, ntscPictureTexture, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("couldn't set up the NTSC framebuffer");
            }
        }

        glBindFramebuffer(GL_FRAMEBUFFER, ntscFramebuffer);
        glViewport(0, 0, width, height);
        glUseProgram(ntscProgram);
        glUniform1i(glGetUniformLocation(ntscProgram, "picture"), 0);
        glUniform1i(glGetUniformLocation(ntscProgram, "ntscTable"), 1);
        glUniform1i(glGetUniformLocation(ntscProgram, "chunkCount"), (frame.width() - 1) / SnesNtsc.IN_CHUNK);
        // Snes9x steps the first line's burst phase once per frame: 0, 1, 2, 0, ...
        glUniform1i(glGetUniformLocation(ntscProgram, "framePhase"), (int) (frame.number() % SnesNtsc.BURST_COUNT));
        glUniform1i(glGetUniformLocation(ntscProgram, "scanlines"), scanlines ? 1 : 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, rawTexture);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, ntscTableTexture);
        glBindVertexArray(emptyVertexArray);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glActiveTexture(GL_TEXTURE0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Draws the current picture into a window whose drawable area is
     * {@code windowWidth x windowHeight} pixels.
     *
     * @param margin pixels to leave black on every side, outside the picture. The borderless
     *               fullscreen window reaches past the screen's edges, and this keeps the
     *               picture on the visible part.
     */
    void draw(int windowWidth, int windowHeight, int margin) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, windowWidth, windowHeight);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        int areaWidth = windowWidth - 2 * margin;
        int areaHeight = windowHeight - 2 * margin;
        if (areaWidth <= 0 || areaHeight <= 0) {
            return; // minimized
        }

        // The largest rectangle with the picture's shape that fits inside the margins, centered.
        double scale = Math.min(areaWidth / (double) pictureWidth, areaHeight / (double) pictureHeight);
        int width = (int) (pictureWidth * scale);
        int height = (int) (pictureHeight * scale);
        int x = (windowWidth - width) / 2;
        int y = (windowHeight - height) / 2;
        glViewport(x, y, width, height);

        int program = showingNtsc ? presentProgram : sharpProgram;
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "picture"), 0);
        glUniform4f(glGetUniformLocation(program, "viewport"), x, y, width, height);
        if (!showingNtsc) {
            glUniform2i(glGetUniformLocation(program, "pictureSize"), pictureWidth, pictureHeight);
        }
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, showingNtsc ? ntscPictureTexture : rawTexture);
        glBindVertexArray(emptyVertexArray);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    /**
     * Reads back the last NTSC pass's output as 0xRRGGBB, row 0 at the top, for tests that
     * compare it with the Java filter.
     */
    int[] readNtscPicture() {
        int[] pixels = new int[ntscWidth * ntscHeight];
        glBindFramebuffer(GL_FRAMEBUFFER, ntscFramebuffer);
        glReadPixels(0, 0, ntscWidth, ntscHeight, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] &= 0xFFFFFF; // drop alpha
        }
        return pixels;
    }

    /** Frees the OpenGL objects. */
    void delete() {
        glDeleteProgram(sharpProgram);
        glDeleteProgram(ntscProgram);
        glDeleteProgram(presentProgram);
        glDeleteVertexArrays(emptyVertexArray);
        glDeleteFramebuffers(ntscFramebuffer);
        glDeleteTextures(rawTexture);
        glDeleteTextures(ntscTableTexture);
        glDeleteTextures(ntscPictureTexture);
    }

    private static int createTexture(int filter) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return texture;
    }

    private static int compile(int type, String name) {
        int shader = glCreateShader(type);
        glShaderSource(shader, readShader(name));
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Shader " + name + " didn't compile:\n" + glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private static int link(int vertex, int fragment) {
        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Shader program didn't link:\n" + glGetProgramInfoLog(program));
        }
        glDetachShader(program, vertex);
        glDetachShader(program, fragment);
        glDeleteShader(fragment);
        return program;
    }

    private static String readShader(String name) {
        try (InputStream in = GlRenderer.class.getResourceAsStream(SHADER_DIRECTORY + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing shader resource " + SHADER_DIRECTORY + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
