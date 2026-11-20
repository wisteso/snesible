package snesible.tools;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import snesible.core.cartridge.Cartridge;
import snesible.ui.Frame;
import snesible.core.system.Snes;
import snesible.ui.video.VideoFilter;
import snesible.ui.video.VideoMode;

/**
 * Runs a game without a window and saves a frame as a PNG. Handy for checking rendering
 * changes, and for regression comparisons.
 *
 * <p>Options among the button presses: {@code --video <mode>} draws the picture through a
 * {@link VideoMode} (e.g. {@code composite}, {@code svideo}, {@code rgb}); {@code --scanlines}
 * adds Snes9x's NTSC scanlines.
 */
public final class Screenshot {

    private Screenshot() {
    }

    /**
     * @param arguments entries like {@code START@200} (hold Start from frame 200 for a few
     *                  frames), plus the options above
     */
    public static void take(Path rom, int frames, Path output, String[] arguments) throws IOException {
        VideoMode mode = VideoMode.SHARP;
        boolean scanlines = false;
        List<String> presses = new ArrayList<>();
        for (int i = 0; i < arguments.length; i++) {
            switch (arguments[i]) {
                case "--video" -> mode = VideoMode.parse(arguments[++i]);
                case "--scanlines" -> scanlines = true;
                default -> presses.add(arguments[i]);
            }
        }

        InputScript input = new InputScript(presses.toArray(String[]::new));
        Snes snes = new Snes(Cartridge.load(rom));
        snes.reset();
        for (int frame = 1; frame <= frames; frame++) {
            snes.joypads().setPressed(0, input.buttonsAt(frame));
            snes.runFrame();
        }

        Frame frame = new Frame(snes.ppu().frameBuffer(), 256, snes.ppu().visibleLines(), frames);
        VideoFilter filter = new VideoFilter(mode, scanlines);
        int width = filter.outputWidth(frame.width());
        int height = filter.outputHeight(frame.height());
        int[] rgb = new int[width * height];
        filter.apply(frame, rgb);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, rgb, 0, width);
        ImageIO.write(image, "png", output.toFile());
        System.out.println("Saved frame " + frames + " (" + mode.label() + ") to " + output);
    }
}
