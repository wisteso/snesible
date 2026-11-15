package jfc.ui.lwjgl;

import static org.lwjgl.opengl.GL33C.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import jfc.ui.Frame;
import jfc.ui.video.VideoFilter;

/**
 * Draws frames with OpenGL 3.3: owns the textures, the shader programs and the drawing.
 *
 * <h2>Two ways a frame reaches the screen</h2>
 * <ul>
 *   <li><b>Sharp pixels</b>: the PPU's raw pixels (master brightness and 15-bit color packed in
 *       an int) are uploaded unchanged into an integer texture ({@code GL_R32UI}), and the
 *       fragment shader {@code snes-pixels.frag} turns each into RGB. The CPU does no
 *       conversion at all.</li>
 *   <li><b>NTSC modes</b>: the Java NTSC filter ({@link VideoFilter}) makes a 602 x 448 RGB
 *       picture on the CPU, which is uploaded to an ordinary color texture and scaled smoothly
 *       by {@code rgb-picture.frag}. (Milestone 10d moves the NTSC filter onto the GPU.)</li>
 * </ul>
 *
 * <p>Either way the picture is drawn into a <i>viewport</i>: the largest rectangle with the
 * picture's shape that fits the window, centered, with black bars around it.
 *
 * <p>All methods must be called on the thread that owns the OpenGL context (the main thread).
 */
final class GlRenderer {

    private static final String SHADER_DIRECTORY = "/jfc/ui/lwjgl/shaders/";

    private final int sharpProgram;
    private final int rgbProgram;
    /** OpenGL 3.3 core needs a vertex array object bound to draw, even with no vertex data. */
    private final int emptyVertexArray;
    private final int rawTexture;
    private final int rgbTexture;

    // Texture sizes as allocated; reallocated when a picture of another size arrives.
    private int rawWidth;
    private int rawHeight;
    private int rgbWidth;
    private int rgbHeight;

    // What the next draw shows.
    private boolean showingRgb;
    private int pictureWidth = 256;
    private int pictureHeight = 224;
    private int[] filtered = new int[0];

    GlRenderer() {
        int vertex = compile(GL_VERTEX_SHADER, "fullscreen.vert");
        sharpProgram = link(vertex, compile(GL_FRAGMENT_SHADER, "snes-pixels.frag"));
        rgbProgram = link(vertex, compile(GL_FRAGMENT_SHADER, "rgb-picture.frag"));
        glDeleteShader(vertex);
        emptyVertexArray = glGenVertexArrays();

        rawTexture = createTexture(GL_NEAREST); // integer textures can't be filtered anyway
        rgbTexture = createTexture(GL_LINEAR);
    }

    /**
     * Takes a new frame: uploads its raw pixels, or (in an NTSC mode) runs the filter and
     * uploads the result. The frame's array is only read during this call.
     */
    void upload(Frame frame, VideoFilter filter) {
        if (filter.mode().ntscSetup() == null) {
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
            showingRgb = false;
            pictureWidth = frame.width();
            pictureHeight = frame.height();
            return;
        }
        int width = filter.outputWidth(frame.width());
        int height = filter.outputHeight(frame.height());
        if (filtered.length < width * height) {
            filtered = new int[width * height];
        }
        filter.apply(frame, filtered);
        glBindTexture(GL_TEXTURE_2D, rgbTexture);
        if (rgbWidth != width || rgbHeight != height) {
            rgbWidth = width;
            rgbHeight = height;
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, (ByteBuffer) null);
        }
        // Each int is 0x00RRGGBB. Read as a 32-bit value with the "BGRA, reversed" layout, its
        // low byte is blue, then green, then red, then alpha (unused): exactly our ints.
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, filtered);
        showingRgb = true;
        pictureWidth = width;
        pictureHeight = height;
    }

    /**
     * Draws the current picture into a window whose drawable area is
     * {@code windowWidth x windowHeight} pixels.
     */
    void draw(int windowWidth, int windowHeight) {
        glViewport(0, 0, windowWidth, windowHeight);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        if (windowWidth <= 0 || windowHeight <= 0) {
            return; // minimized
        }

        // The largest rectangle with the picture's shape that fits, centered.
        double scale = Math.min(windowWidth / (double) pictureWidth, windowHeight / (double) pictureHeight);
        int width = (int) (pictureWidth * scale);
        int height = (int) (pictureHeight * scale);
        int x = (windowWidth - width) / 2;
        int y = (windowHeight - height) / 2;
        glViewport(x, y, width, height);

        int program = showingRgb ? rgbProgram : sharpProgram;
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "picture"), 0);
        glUniform4f(glGetUniformLocation(program, "viewport"), x, y, width, height);
        if (!showingRgb) {
            glUniform2i(glGetUniformLocation(program, "pictureSize"), pictureWidth, pictureHeight);
        }
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, showingRgb ? rgbTexture : rawTexture);
        glBindVertexArray(emptyVertexArray);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    /** Frees the OpenGL objects. */
    void delete() {
        glDeleteProgram(sharpProgram);
        glDeleteProgram(rgbProgram);
        glDeleteVertexArrays(emptyVertexArray);
        glDeleteTextures(rawTexture);
        glDeleteTextures(rgbTexture);
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
