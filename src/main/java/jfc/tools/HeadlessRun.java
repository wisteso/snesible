package jfc.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import jfc.core.cartridge.Cartridge;
import jfc.core.cpu.Registers;
import jfc.core.system.Snes;

/** {@code jfc --run}: runs a game without video and reports where the CPU spends its time. */
public final class HeadlessRun {

    private HeadlessRun() {
    }

    /**
     * Runs a game with no display, printing a progress line every 10 frames. Good for checking
     * that a game boots: once it reaches its main loop, the CPU spends each frame in the same
     * few instructions, waiting for the NMI.
     */
    public static void run(Path path, int frames, String[] presses) throws IOException {
        InputScript input = new InputScript(presses);
        Cartridge cart = Cartridge.load(path);
        Snes snes = new Snes(cart);
        int nmiHandler = cart.header().vectors().nativeNmi();
        Map<Integer, Integer> pcCounts = new HashMap<>();
        int[] nmiCount = {0};
        long[] instructions = {0};
        snes.cpu().setInstructionListener(r -> {
            int address = (r.pbr << 16) | r.pc;
            pcCounts.merge(address, 1, Integer::sum);
            if (address == nmiHandler) {
                nmiCount[0]++;
            }
            instructions[0]++;
        });

        System.out.println("Running " + cart.header().title() + " for " + frames + " frames");
        snes.reset();
        for (int frame = 1; frame <= frames; frame++) {
            pcCounts.clear();
            snes.joypads().setPressed(0, input.buttonsAt(frame));
            snes.runFrame();
            if (frame % 10 == 0 || frame == frames) {
                Registers r = snes.cpu().registers();
                System.out.printf("  frame %4d  PC=%02X:%04X  NMIs=%d  instructions/frame=%d  %s%n",
                        frame, r.pbr, r.pc, nmiCount[0], instructions[0] / frame,
                        snes.ppu().isForcedBlank() ? "(screen blanked)"
                                : "(screen on, mode " + snes.ppu().bgMode() + ")");
            }
        }

        System.out.println("Most executed addresses in the last frame:");
        pcCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(8)
                .forEach(e -> System.out.printf("  %02X:%04X  %6d times%n",
                        e.getKey() >> 16, e.getKey() & 0xFFFF, e.getValue()));
        System.out.println("Final state: " + snes.cpu().registers());
    }
}
