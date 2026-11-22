package snesible.ui.input;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import snesible.ui.Platform;

/**
 * The button mappings of {@code gamepad.properties}: one for each model of pad the file has a
 * section for, and one for every other pad.
 *
 * <h2>The file</h2>
 * <pre>
 *   # Pads without a section of their own (an SNES-style USB pad, say).
 *   A=0
 *   B=1
 *
 *   [2dc8:6009]
 *   # A pad with paddles and two pairs of shoulder buttons.
 *   A=0
 *   B=1
 *   X=3
 *   Y=4
 *   L=5,6,8
 *   R=2,7,9
 *   SELECT=10
 *   START=11
 *
 *   [2dc8:6009 windows]
 *   # Only on Windows: this pad numbers one button differently there.
 *   SELECT=12
 * </pre>
 * A section is named by the pad's USB vendor and product numbers ({@link UsbId}), which
 * {@code --gamepad-test} prints, so it follows that model of pad from computer to computer.
 * The system's drivers number the buttons, though, and they don't always agree: macOS, Windows
 * and Linux can list one pad's buttons in different orders. A section can therefore add a
 * system name ({@code windows}, {@code mac} or {@code linux}) whose lines apply on top of the
 * plain section, on that system only.
 *
 * <h2>Which lines a pad gets</h2>
 * Every mapping starts from {@link GamepadMapping}'s built-in default (buttons 0-7 are A, B, X,
 * Y, L, R, Select, Start). Then:
 * <ul>
 *   <li>A pad with a section for its model (plain, this system's, or both) gets the plain
 *       section's lines, then this system's.</li>
 *   <li>Any other pad, or one whose numbers aren't known, gets the lines before the first
 *       section. A file with no sections, as older versions wrote, is just those lines.</li>
 * </ul>
 * The lines before the first section don't apply to a pad with its own section: they're
 * usually for a different pad, whose numbering would only get in the way.
 *
 * <p>This only matters for pads read by their raw button numbers. The OpenGL window maps pads
 * in GLFW's gamepad database by position and doesn't use this file for them.
 */
public final class GamepadMappings {

    /** The system names a section can add after the pad's numbers. */
    private static final Set<String> SYSTEMS = Set.of("windows", "mac", "linux");

    /** One line of the file: an SNES button name and its device buttons. */
    private record Line(String name, String value, String where) {
    }

    /** The lines before the first section, for pads without a section of their own. */
    private final List<Line> others = new ArrayList<>();
    /** Each section's lines, by its name in lowercase: "2dc8:6009" or "2dc8:6009 mac". */
    private final Map<String, List<Line>> sections = new LinkedHashMap<>();
    /** The system whose sections apply: {@link Platform#shortName()}, or a test's choice. */
    private final String system;

    private GamepadMappings(String system) {
        this.system = system;
    }

    /** No file: every pad gets the built-in default. */
    public static GamepadMappings defaults() {
        return new GamepadMappings(Platform.shortName());
    }

    /** Reads {@code file}, or returns {@link #defaults()} if there's no such file. */
    public static GamepadMappings load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return defaults();
        }
        return parse(Files.readAllLines(file), file.toString(), Platform.shortName());
    }

    /**
     * Parses the file's lines as if on {@code system} ("windows", "mac", "linux"). Package-private
     * so tests can try every system on any machine.
     *
     * <p>The format is that of a Java properties file (the file's earlier versions were read
     * as one) plus section headers, which {@link java.util.Properties} doesn't know. It keeps to
     * the simple part: {@code name=value} lines, and comments starting with {@code #} or
     * {@code !}.
     */
    static GamepadMappings parse(List<String> lines, String fileName, String system) throws IOException {
        GamepadMappings mappings = new GamepadMappings(system);
        List<Line> current = mappings.others;
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i).strip();
            String where = fileName + " line " + (i + 1);
            if (text.isEmpty() || text.startsWith("#") || text.startsWith("!")) {
                continue;
            }
            if (text.startsWith("[")) {
                if (!text.endsWith("]")) {
                    throw new IOException(where + ": a section header needs a closing ']'");
                }
                String section = sectionName(text.substring(1, text.length() - 1), where);
                current = mappings.sections.computeIfAbsent(section, name -> new ArrayList<>());
                continue;
            }
            int equals = text.indexOf('=');
            if (equals < 0) {
                throw new IOException(where + ": expected BUTTON=number, such as START=7");
            }
            current.add(new Line(text.substring(0, equals), text.substring(equals + 1), where));
        }
        // Check every line now, so a mistake shows when the file is read rather than only when
        // that pad is plugged in.
        mappings.build(mappings.others);
        for (List<Line> section : mappings.sections.values()) {
            mappings.build(section);
        }
        return mappings;
    }

    /**
     * A section header's text in its normal form ("2dc8:6009" or "2dc8:6009 mac"), checking
     * that it is one.
     */
    private static String sectionName(String header, String where) throws IOException {
        String[] words = header.strip().toLowerCase(Locale.ROOT).split("\\s+");
        UsbId id = words.length >= 1 ? UsbId.parse(words[0]) : null;
        if (id == null || words.length > 2 || (words.length == 2 && !SYSTEMS.contains(words[1]))) {
            throw new IOException(where + ": a section is [vendor:product] or [vendor:product system],"
                    + " such as [2dc8:6009] or [2dc8:6009 mac] (systems: windows, mac, linux)");
        }
        return words.length == 1 ? id.toString() : id + " " + words[1];
    }

    /** The mapping for a pad of model {@code id}, or for a pad whose model isn't known (null). */
    public GamepadMapping forDevice(UsbId id) {
        try {
            List<String> used = sectionsFor(id);
            if (used.isEmpty()) {
                return build(others);
            }
            List<Line> lines = new ArrayList<>();
            for (String section : used) {
                lines.addAll(sections.get(section));
            }
            return build(lines);
        } catch (IOException e) {
            // parse() has already built every section once, so this can't happen.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Where the mapping for {@code id} comes from, for messages: "[2dc8:6009] + [2dc8:6009 mac]
     * in gamepad.properties", "the lines before the first section", ...
     */
    public String describe(UsbId id) {
        List<String> used = sectionsFor(id);
        if (!used.isEmpty()) {
            return "section " + String.join(" + ", used.stream().map(s -> "[" + s + "]").toList())
                    + " of gamepad.properties";
        }
        return others.isEmpty() ? "the default mapping" : "the default mapping with gamepad.properties' lines before any section";
    }

    /** The sections that apply to {@code id} on this system, plain one first. */
    private List<String> sectionsFor(UsbId id) {
        List<String> used = new ArrayList<>();
        if (id != null) {
            if (sections.containsKey(id.toString())) {
                used.add(id.toString());
            }
            if (sections.containsKey(id + " " + system)) {
                used.add(id + " " + system);
            }
        }
        return used;
    }

    /** The built-in default with {@code lines} applied in order. */
    private GamepadMapping build(List<Line> lines) throws IOException {
        GamepadMapping mapping = new GamepadMapping();
        for (Line line : lines) {
            mapping.set(line.name(), line.value(), line.where());
        }
        return mapping;
    }
}
