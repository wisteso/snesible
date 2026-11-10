package jfc;

import java.io.IOException;
import java.nio.file.Path;
import jfc.cartridge.Cartridge;
import jfc.cartridge.CartridgeHeader;

/** Command-line entry point. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length >= 2 && args[0].equals("--info")) {
            for (int i = 1; i < args.length; i++) {
                printCartridgeInfo(Path.of(args[i]));
            }
            return;
        }
        System.err.println("Usage: jfc --info <rom-file>...   Print the cartridge header of each ROM");
        System.exit(1);
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
