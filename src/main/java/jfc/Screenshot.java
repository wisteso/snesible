package jfc;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import jfc.cartridge.Cartridge;
import jfc.system.Snes;
import jfc.video.SnesColor;

/**
 * Runs a game without a window and saves a frame as a PNG. Handy for checking rendering
 * changes, and for regression comparisons.
 */
final class Screenshot {

    private Screenshot() {
    }

    /**
     * @param presses entries like {@code START@200}: hold Start from frame 200 for a few frames
     */
    static void take(Path rom, int frames, Path output, String[] presses) throws IOException {
        InputScript input = new InputScript(presses);
        Snes snes = new Snes(Cartridge.load(rom));
        snes.reset();
        for (int frame = 1; frame <= frames; frame++) {
            snes.joypads().setPressed(0, input.buttonsAt(frame));
            snes.runFrame();
        }

        int width = 256;
        int height = snes.ppu().visibleLines();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] rgb = new int[width * height];
        SnesColor.toRgb(snes.ppu().frameBuffer(), rgb, rgb.length);
        image.setRGB(0, 0, width, height, rgb, 0, width);
        ImageIO.write(image, "png", output.toFile());
        System.out.println("Saved frame " + frames + " to " + output);
    }
}
