package jfc.cartridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Sanity checks against whatever ROMs are in the (git-ignored) {@code roms/} folder. Skipped
 * when that folder is absent.
 */
class RealRomTest {

    private static final Path ROM_DIR = Path.of("roms");

    static Stream<Path> romFiles() throws IOException {
        if (!Files.isDirectory(ROM_DIR)) {
            return Stream.of(Path.of("<no roms folder>"));
        }
        return Files.list(ROM_DIR)
                .filter(p -> p.toString().matches("(?i).*\\.(sfc|smc)$"))
                .sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("romFiles")
    void headerChecksumMatchesComputedChecksum(Path rom) throws IOException {
        assumeTrue(Files.isRegularFile(rom), "no ROMs available");

        Cartridge cart = Cartridge.load(rom);

        assertEquals(cart.header().checksum(), cart.computeChecksum());
        assertFalse(cart.header().hasCoprocessor(), "coprocessor carts aren't supported yet");
    }
}
