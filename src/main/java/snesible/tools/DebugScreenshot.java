package snesible.tools;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import snesible.core.cartridge.Cartridge;
import snesible.core.observe.Observable;
import snesible.core.system.Snes;
import snesible.debug.MemoryObserver;
import snesible.ui.debug.DebugScreen;

/**
 * Runs a game headless with the memory debugger attached and saves the debugger's view (the
 * heat map) as a PNG, the same picture its window would show. This is how the debugger's
 * output is checked without opening windows.
 * <pre>
 *   snesible --debug-screenshot game.sfc 600 out.png [--space VRAM] [--size 1024x800] [START@200 ...]
 * </pre>
 * {@code --space} picks the tab (WRAM, ROM, Cart RAM, I-RAM, VRAM, CGRAM, OAM, ARAM; default
 * WRAM). Button presses work as in {@link Screenshot}.
 *
 * <p><b>Debug support:</b> a tool for the visual memory debugger.
 */
public final class DebugScreenshot {

    private DebugScreenshot() {
    }

    public static void take(Path rom, int frames, Path output, String[] arguments) throws IOException {
        String space = "WRAM";
        int width = 1024;
        int height = 800;
        List<String> presses = new ArrayList<>();
        for (int i = 0; i < arguments.length; i++) {
            switch (arguments[i]) {
                case "--space" -> space = arguments[++i];
                case "--size" -> {
                    String[] parts = arguments[++i].split("x");
                    width = Integer.parseInt(parts[0]);
                    height = Integer.parseInt(parts[1]);
                }
                default -> presses.add(arguments[i]);
            }
        }

        // The same steps as DebugMain.createSnes: install, build, attach.
        Cartridge cartridge = Cartridge.load(rom);
        MemoryObserver observer = new MemoryObserver();
        Observable.install(observer);
        Snes snes = new Snes(cartridge);
        observer.attach(snes);
        Observable.install(null);
        DebugScreen screen = new DebugScreen(observer, cartridge.header().title());
        if (!screen.select(space)) {
            throw new IllegalArgumentException("No memory called " + space + "; this game has " + screen.spaceNames());
        }

        InputScript input = new InputScript(presses.toArray(String[]::new));
        snes.reset();
        long started = System.nanoTime();
        for (int frame = 1; frame <= frames; frame++) {
            snes.joypads().setPressed(0, input.buttonsAt(frame));
            snes.runFrame();
            screen.afterFrame();
        }
        long emulation = System.nanoTime() - started;

        int[] argb = new int[width * height];
        // Draw a few times and keep the fastest: the first runs include JIT compilation, and
        // the window redraws every frame, so the warmed-up time is the one that matters.
        long render = Long.MAX_VALUE;
        for (int i = 0; i < 20; i++) {
            long renderStart = System.nanoTime();
            screen.render(argb, width, height);
            render = Math.min(render, System.nanoTime() - renderStart);
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        ImageIO.write(image, "png", output.toFile());
        System.out.printf("Saved the %s heat map after frame %d to %s (%.2f ms/frame emulating and"
                        + " recording, %.1f ms to draw)%n",
                space, frames, output, emulation / 1e6 / frames, render / 1e6);
    }
}
