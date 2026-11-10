package jfc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import jfc.cartridge.Cartridge;
import jfc.cartridge.CartridgeHeader;
import jfc.cpu.Registers;
import jfc.system.Snes;
import jfc.ui.EmulatorWindow;

/** Command-line entry point. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && !args[0].startsWith("--")) {
            Cartridge cart = Cartridge.load(Path.of(args[0]));
            new EmulatorWindow(new Snes(cart), cart.header().title()).start();
            return;
        }
        if (args.length >= 2 && args[0].equals("--info")) {
            for (int i = 1; i < args.length; i++) {
                printCartridgeInfo(Path.of(args[i]));
            }
            return;
        }
        if (args.length >= 2 && args[0].equals("--run")) {
            int frames = args.length >= 3 ? Integer.parseInt(args[2]) : 120;
            String[] presses = args.length > 3 ? java.util.Arrays.copyOfRange(args, 3, args.length) : new String[0];
            runHeadless(Path.of(args[1]), frames, new InputScript(presses));
            return;
        }
        if (args.length >= 4 && args[0].equals("--screenshot")) {
            String[] presses = java.util.Arrays.copyOfRange(args, 4, args.length);
            Screenshot.take(Path.of(args[1]), Integer.parseInt(args[2]), Path.of(args[3]), presses);
            return;
        }
        System.err.println("""
                Usage:
                  jfc <rom-file>                 Play a game in a window
                  jfc --info <rom-file>...       Print the cartridge header of each ROM
                  jfc --run <rom-file> [frames] [BUTTON@frame ...]
                                                 Run without video and report what the CPU does
                  jfc --screenshot <rom-file> <frames> <out.png> [BUTTON@frame ...]
                                                 Run headless and save the last frame as a PNG,
                                                 pressing buttons (e.g. START@200) on the way""");
        System.exit(1);
    }

    /**
     * Runs a game with no display, printing a progress line every 10 frames. Good for checking
     * that a game boots: once it reaches its main loop, the CPU spends each frame in the same
     * few instructions, waiting for the NMI.
     */
    private static void runHeadless(Path path, int frames, InputScript input) throws IOException {
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

    private static void printCartridgeInfo(Path path) throws IOException {
        Cartridge cart = Cartridge.load(path);
        CartridgeHeader h = cart.header();
        CartridgeHeader.Vectors v = h.vectors();
        int computed = cart.computeChecksum();

        System.out.println(path.getFileName());
        row("Title", h.title());
        row("Layout", cart.mapMode() + ", " + (h.isFastRom() ? "FastROM" : "SlowROM")
                + "  (map mode byte " + hex2(h.mapModeByte())
                + ", header at file offset " + hex(h.headerFileOffset(), 6) + ")");
        row("Cartridge", h.describeCartridgeType() + "  (type byte " + hex2(h.cartridgeTypeByte()) + ")");
        row("ROM", kib(cart.romSize()) + "  (header says " + kib(h.declaredRomSizeBytes()) + ")");
        row("SRAM", h.sramSizeBytes() == 0 ? "none" : kib(h.sramSizeBytes()));
        row("Region", h.region().toString());
        row("Developer", hex2(h.developerId()) + ", version 1." + h.version());
        row("Checksum", hex(h.checksum(), 4) + " / complement " + hex(h.checksumComplement(), 4)
                + "  computed " + hex(computed, 4)
                + (computed == h.checksum() ? "  OK" : "  MISMATCH"));
        row("Copier header", cart.hadCopierHeader() ? "yes (512 bytes, stripped)" : "no");
        row("Native vectors", "COP " + hex(v.nativeCop(), 4) + "  BRK " + hex(v.nativeBrk(), 4)
                + "  NMI " + hex(v.nativeNmi(), 4) + "  IRQ " + hex(v.nativeIrq(), 4));
        row("Emulation vec.", "RESET " + hex(v.emulationReset(), 4) + "  NMI " + hex(v.emulationNmi(), 4)
                + "  IRQ/BRK " + hex(v.emulationIrqBrk(), 4));
        System.out.println();
    }

    private static void row(String label, String value) {
        System.out.printf("  %-15s %s%n", label, value);
    }

    private static String hex(int value, int digits) {
        return "$" + String.format("%0" + digits + "X", value);
    }

    private static String hex2(int value) {
        return hex(value, 2);
    }

    private static String kib(int bytes) {
        return (bytes / 1024) + " KiB";
    }
}
