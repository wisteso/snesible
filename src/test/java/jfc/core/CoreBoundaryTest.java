package jfc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the emulated console independent of any user interface: nothing in {@code jfc.core} may
 * use Swing, AWT, LWJGL or the {@code jfc.ui} and {@code jfc.tools} packages. The UI depends
 * on the core, never the other way round, which is what lets Swing and LWJGL windows (and the
 * headless tools) share one emulator.
 */
class CoreBoundaryTest {

    private static final Path CORE_SOURCES = Path.of("src/main/java/jfc/core");

    private static final List<String> FORBIDDEN = List.of(
            "java.awt", "javax.swing", "javax.imageio", "org.lwjgl", "jfc.ui", "jfc.tools");

    @Test
    void coreUsesNoUserInterfaceCode() throws IOException {
        assertTrue(Files.isDirectory(CORE_SOURCES), "run from the project root");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CORE_SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    for (String pkg : FORBIDDEN) {
                        // Imports, and fully qualified uses in code (but not in comments).
                        boolean imports = line.startsWith("import " + pkg + ".")
                                || line.startsWith("import static " + pkg + ".");
                        boolean qualified = !line.startsWith("*") && !line.startsWith("//")
                                && line.contains(pkg + ".") && !line.startsWith("import ");
                        if (imports || qualified) {
                            violations.add(CORE_SOURCES.relativize(file) + ":" + (i + 1) + "  " + line);
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), violations, "jfc.core must not depend on UI code");
    }
}
