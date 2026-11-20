package snesible.core;

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
 * Keeps the emulated console independent of any user interface: nothing in {@code snesible.core} may
 * use Swing, AWT, LWJGL or the {@code snesible.ui}, {@code snesible.tools} and {@code snesible.debug} packages.
 * (The debugger reaches into the core only through {@code snesible.core.observe}, never the reverse.) The UI depends
 * on the core, never the other way round, which is what lets Swing and LWJGL windows (and the
 * headless tools) share one emulator.
 *
 * <p><b>What it checks.</b> Every line of every source file under {@code src/main/java/snesible/core}:
 * an {@code import} (or {@code import static}) of a forbidden package fails, and so does a
 * fully qualified use in code, such as {@code javax.swing.JFrame f}.
 *
 * <p><b>What's allowed.</b> Comments may <i>mention</i> UI classes: core Javadoc points to
 * {@code snesible.ui.video.SnesColor} to say who turns the frame buffer into RGB, which documents the
 * boundary without crossing it. Lines starting with {@code *} or {@code //} are skipped for
 * that reason.
 *
 * <p><b>Limits.</b> It's a plain text scan, not a parser, chosen to stay simple and
 * dependency-free. It would flag a forbidden name inside a string literal or in a trailing
 * {@code //} comment after code, and it would miss a use hidden behind reflection
 * ({@code Class.forName("javax.swing...")}). Both are unlikely in emulation code, and a false
 * alarm is easy to look at.
 */
class CoreBoundaryTest {

    private static final Path CORE_SOURCES = Path.of("src/main/java/snesible/core");

    private static final List<String> FORBIDDEN = List.of(
            "java.awt", "javax.swing", "javax.imageio", "org.lwjgl", "snesible.ui", "snesible.tools",
            "snesible.debug");

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
        assertEquals(List.of(), violations, "snesible.core must not depend on UI code");
    }
}
