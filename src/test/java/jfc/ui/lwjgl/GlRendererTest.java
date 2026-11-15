package jfc.ui.lwjgl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

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
 * Draws frames through the real OpenGL pipeline in a hidden window and reads the pixels back.
 * Skipped on machines without OpenGL 3.3 (a build server, say).
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
        renderer.upload(new Frame(pixels, 256, 224, 1), new VideoFilter(VideoMode.SHARP, false));
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
    void theNtscPictureIsDrawnAsFiltered() {
        // A flat color, so smooth scaling can't change it: the window must show what the
        // Java filter made.
        int[] pixels = new int[256 * 239];
        java.util.Arrays.fill(pixels, SnesColor.pack(0x2A6B, 15));
        VideoFilter composite = new VideoFilter(VideoMode.COMPOSITE, false);
        Frame frame = new Frame(pixels, 256, 224, 1);
        renderer.upload(frame, composite);
        int[] expected = new int[602 * 448];
        composite.apply(frame, expected);
        int[] drawn = drawAndRead(602, 448);
        assertEquals(expected[224 * 602 + 300], drawn[224 * 602 + 300] & 0xFFFFFF);
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
