package jfc.ui.lwjgl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.util.Random;
import jfc.ui.Frame;
import jfc.ui.video.SnesColor;
import jfc.ui.video.VideoFilter;
import jfc.ui.video.VideoMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

/**
 * Draws frames through the real OpenGL pipeline in a hidden window and reads the pixels back:
 * the GPU must produce exactly what the Java code does. Skipped on machines without OpenGL 3.3
 * (a build server, say).
 */
class GlRendererTest {

    private static long window = NULL;
    private static GlRenderer renderer;

    @BeforeAll
    static void createContext() {
        LwjglWindow.useFfmMemoryAccess();
        boolean available = glfwInit();
        if (available) {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            window = glfwCreateWindow(256, 224, "test", NULL, NULL);
        }
        Assumptions.assumeTrue(window != NULL, "no OpenGL 3.3 here");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        renderer = new GlRenderer();
    }

    @AfterAll
    static void destroyContext() {
        if (renderer != null) {
            renderer.delete();
        }
        if (window != NULL) {
            glfwDestroyWindow(window);
        }
        glfwTerminate();
    }

    @Test
    void sharpPixelsMatchTheSoftwareConversionExactly() {
        // Every pixel a different color and brightness, covering all 32 levels of each channel
        // and all 16 brightness levels.
        int[] pixels = new int[256 * 239];
        for (int y = 0; y < 224; y++) {
            for (int x = 0; x < 256; x++) {
                int color = (x % 32) | ((y % 32) << 5) | (((x / 32 + y / 32 * 8) % 32) << 10);
                int brightness = (x + y) % 16;
                pixels[y * 256 + x] = SnesColor.pack(color, brightness);
            }
        }
        renderer.upload(new Frame(pixels, 256, 224, 1), VideoMode.SHARP, false);
        int[] drawn = drawAndRead(256, 224);
        for (int y = 0; y < 224; y++) {
            for (int x = 0; x < 256; x++) {
                // OpenGL's rows start at the bottom.
                int gl = drawn[(223 - y) * 256 + x] & 0xFFFFFF;
                assertEquals(SnesColor.toRgb(pixels[y * 256 + x]), gl, "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void theNtscShaderMatchesTheJavaFilterExactly() {
        // Random colors and brightness, plus solid runs and fine stripes, which bring out the
        // artifacts. All three burst phases (frames 1-3), with and without scanlines, for the
        // composite, S-Video and RF setups.
        int[] pixels = testPicture();
        for (VideoMode mode : new VideoMode[] {VideoMode.COMPOSITE, VideoMode.SVIDEO, VideoMode.RF}) {
            for (boolean scanlines : new boolean[] {false, true}) {
                VideoFilter java = new VideoFilter(mode, scanlines);
                for (long frameNumber = 1; frameNumber <= 3; frameNumber++) {
                    Frame frame = new Frame(pixels, 256, 224, frameNumber);
                    renderer.upload(frame, mode, scanlines);
                    int[] gpu = renderer.readNtscPicture();
                    int[] expected = new int[602 * 448];
                    java.apply(frame, expected);
                    assertEquals(expected.length, gpu.length);
                    for (int i = 0; i < expected.length; i++) {
                        if (expected[i] != gpu[i]) {
                            fail(String.format("%s, scanlines %b, frame %d: pixel %d,%d is %06X on the GPU, %06X in Java",
                                    mode, scanlines, frameNumber, i % 602, i / 602, gpu[i], expected[i]));
                        }
                    }
                }
            }
        }
    }

    private static int[] testPicture() {
        Random random = new Random(1234);
        int[] pixels = new int[256 * 239];
        for (int y = 0; y < 224; y++) {
            for (int x = 0; x < 256; x++) {
                int color;
                if (y < 80) {
                    color = random.nextInt(0x8000); // noise
                } else if (y < 150) {
                    color = x % 2 == 0 ? 0x7FFF : 0; // one-pixel stripes
                } else {
                    color = (x / 16) * 0x0842 & 0x7FFF; // wide bands
                }
                int brightness = y < 200 ? 15 : random.nextInt(16);
                pixels[y * 256 + x] = SnesColor.pack(color, brightness);
            }
        }
        return pixels;
    }

    /** Draws into the window's framebuffer at the given size and reads it back as 0xAARRGGBB. */
    private static int[] drawAndRead(int width, int height) {
        glfwSetWindowSize(window, width, height);
        int[] w = new int[1];
        int[] h = new int[1];
        glfwGetFramebufferSize(window, w, h);
        Assumptions.assumeTrue(w[0] == width && h[0] == height, "display scaling changed the size");
        renderer.draw(width, height);
        glFinish();
        int[] out = new int[width * height];
        glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, out);
        return out;
    }
}
