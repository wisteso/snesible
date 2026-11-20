package snesible.ui.lwjgl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.util.Random;
import snesible.ui.Frame;
import snesible.ui.video.SnesColor;
import snesible.ui.video.VideoFilter;
import snesible.ui.video.VideoMode;
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
        renderer.draw(0, width, height, 0, false);
        glFinish();
        int[] out = new int[width * height];
        glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, out);
        return out;
    }

    // ---- Scanlines (crt-scanlines.frag) ------------------------------------------------------

    /** Window pixels per SNES line in the scanline tests. */
    private static final int ZOOM = 8;

    @Test
    void scanlinesHaveDarkGapsBetweenDarkLinesAndNearlyNoneBetweenBrightOnes() {
        int[] gray = drawScanlines(flatFrame(0x3DEF, 15), VideoMode.SHARP); // 15/31 gray
        int[] white = drawScanlines(flatFrame(0x7FFF, 15), VideoMode.SHARP);
        int x = 1000;
        int line = 100;
        int center = line * ZOOM + ZOOM / 2; // the first window row below the line's center
        int gap = line * ZOOM;               // the row at the boundary between two lines

        int grayCenter = green(gray, x, center);
        int grayGap = green(gray, x, gap);
        assertTrue(grayCenter >= green(SnesColor.toRgb(SnesColor.pack(0x3DEF, 15))) - 2,
                "a line's center keeps (about) its color: " + grayCenter);
        // Gamma encoding makes the gap look less dark in these numbers than in linear light.
        assertTrue(grayGap < grayCenter * 0.85, "gray lines have clear gaps: " + grayGap + " vs " + grayCenter);

        int whiteCenter = green(white, x, center);
        int whiteGap = green(white, x, gap);
        assertTrue(whiteCenter >= 250, "white stays white at a line's center: " + whiteCenter);
        assertTrue(whiteGap > whiteCenter * 0.9, "bright beams spread and close the gaps: " + whiteGap);
        assertTrue(whiteGap / (double) whiteCenter > grayGap / (double) grayCenter,
                "the gap is smaller for brighter lines");
    }

    @Test
    void blackStaysBlackAndTheGapsStayPutFromFrameToFrame() {
        int[] black = drawScanlines(flatFrame(0, 15), VideoMode.SHARP);
        for (int pixel : black) {
            assertEquals(0, pixel & 0xFFFFFF);
        }
        // 240p: every frame's lines land on the same rows.
        int[] first = drawScanlines(flatFrame(0x3DEF, 15), VideoMode.SHARP);
        int[] second = drawScanlines(new Frame(flatFrame(0x3DEF, 15).pixels(), 256, 224, 2), VideoMode.SHARP);
        assertEquals(java.util.Arrays.hashCode(first), java.util.Arrays.hashCode(second));
    }

    @Test
    void aBeamOnlyLightsTheRowsNearItsLine() {
        int[] pixels = new int[256 * 239];
        for (int x = 0; x < 256; x++) {
            pixels[100 * 256 + x] = SnesColor.pack(0x7FFF, 15); // one white line in black
        }
        int[] out = drawScanlines(new Frame(pixels, 256, 224, 1), VideoMode.SHARP);
        int x = 1000;
        assertTrue(green(out, x, 100 * ZOOM + ZOOM / 2) >= 250, "the line itself is lit");
        assertEquals(0, green(out, x, 97 * ZOOM), "three lines above is dark");
        assertEquals(0, green(out, x, 104 * ZOOM), "three lines below is dark");
    }

    @Test
    void ntscPicturesGetTheSameScanlines() {
        int[] out = drawScanlines(flatFrame(0x3DEF, 15), VideoMode.COMPOSITE);
        // The NTSC picture is 602 x 448, so at this size each line is ZOOM rows tall too.
        int x = 1000;
        int center = green(out, x, 100 * ZOOM + ZOOM / 2);
        int gap = green(out, x, 100 * ZOOM);
        assertTrue(gap < center * 0.85, "gaps between NTSC lines: " + gap + " vs " + center);
    }

    private static Frame flatFrame(int bgr15, int brightness) {
        int[] pixels = new int[256 * 239];
        java.util.Arrays.fill(pixels, SnesColor.pack(bgr15, brightness));
        return new Frame(pixels, 256, 224, 1);
    }

    /**
     * Uploads the frame and draws it with scanlines into an offscreen framebuffer sized so each
     * SNES line covers exactly ZOOM rows. Returns 0xRRGGBB pixels, top row first.
     */
    private static int[] drawScanlines(Frame frame, VideoMode mode) {
        renderer.upload(frame, mode, false);
        // Sharp: 256 x 224 at ZOOM. NTSC: 602 x 448, so half the zoom keeps ZOOM rows per line.
        int width = mode == VideoMode.SHARP ? 256 * ZOOM : 602 * ZOOM / 2;
        int height = 224 * ZOOM;
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        int framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        renderer.draw(framebuffer, width, height, 0, true);
        glFinish();
        int[] bottomUp = new int[width * height];
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, bottomUp);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(framebuffer);
        glDeleteTextures(texture);
        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out[y * width + x] = bottomUp[(height - 1 - y) * width + x] & 0xFFFFFF;
            }
        }
        scanlineTestWidth = width;
        return out;
    }

    private static int scanlineTestWidth;

    private static int green(int[] picture, int x, int row) {
        return green(picture[row * scanlineTestWidth + x]);
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }
}
