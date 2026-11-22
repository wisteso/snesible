package snesible.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import snesible.core.cpu.io.JoypadPorts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code gamepad.properties}: sections per model of pad and per system. */
class GamepadMappingsTest {

    private static final int NO_HAT = -1;

    /** An SNES-style pad, which needs only the default, and a pad with paddles. */
    private static final UsbId SNES_STYLE = new UsbId(0x0583, 0x2060);
    private static final UsbId PADDLES = new UsbId(0x2dc8, 0x6009);

    private static final List<String> FILE = List.of(
            "# lines for other pads",
            "A=1",
            "B=0",
            "",
            "[2DC8:6009]",
            "A=0",
            "B=1",
            "L=5,6,8",
            "R=2, 7, 9",
            "SELECT=10",
            "START=11",
            "",
            "[2dc8:6009 windows]",
            "SELECT=12");

    @Test
    void aPadWithASectionGetsItAndNotTheLinesForOtherPads() throws IOException {
        GamepadMapping mapping = GamepadMappings.parse(FILE, "test", "mac").forDevice(PADDLES);

        assertEquals(JoypadPorts.BUTTON_A, mapping.toSnesButtons(buttons(0)));
        for (int deviceButton : new int[] {5, 6, 8}) {
            assertEquals(JoypadPorts.BUTTON_L, mapping.toSnesButtons(buttons(deviceButton)));
        }
        for (int deviceButton : new int[] {2, 7, 9}) {
            assertEquals(JoypadPorts.BUTTON_R, mapping.toSnesButtons(buttons(deviceButton)));
        }
        assertEquals(JoypadPorts.BUTTON_SELECT, mapping.toSnesButtons(buttons(10)), "not the Windows section");
    }

    @Test
    void thisSystemsSectionAppliesOnTop() throws IOException {
        GamepadMapping mapping = GamepadMappings.parse(FILE, "test", "windows").forDevice(PADDLES);

        assertEquals(JoypadPorts.BUTTON_SELECT, mapping.toSnesButtons(buttons(12)));
        assertEquals(0, mapping.toSnesButtons(buttons(10)));
        assertEquals(JoypadPorts.BUTTON_START, mapping.toSnesButtons(buttons(11)), "plain section still applies");
    }

    @Test
    void otherPadsGetTheLinesBeforeTheFirstSection() throws IOException {
        GamepadMappings mappings = GamepadMappings.parse(FILE, "test", "mac");

        for (UsbId id : new UsbId[] {SNES_STYLE, null}) {
            GamepadMapping mapping = mappings.forDevice(id);
            assertEquals(JoypadPorts.BUTTON_A, mapping.toSnesButtons(buttons(1)));
            assertEquals(JoypadPorts.BUTTON_B, mapping.toSnesButtons(buttons(0)));
            assertEquals(JoypadPorts.BUTTON_START, mapping.toSnesButtons(buttons(7)), "default otherwise");
        }
    }

    @Test
    void onlyAnotherSystemsSectionMeansTheLinesForOtherPads() throws IOException {
        List<String> file = List.of("A=1", "[2dc8:6009 linux]", "A=4");

        GamepadMapping mapping = GamepadMappings.parse(file, "test", "mac").forDevice(PADDLES);

        assertEquals(JoypadPorts.BUTTON_A, mapping.toSnesButtons(buttons(1)));
    }

    @Test
    void describeNamesTheSectionsUsed() throws IOException {
        GamepadMappings mappings = GamepadMappings.parse(FILE, "test", "windows");

        assertEquals("section [2dc8:6009] + [2dc8:6009 windows] of gamepad.properties", mappings.describe(PADDLES));
    }

    @Test
    void badSectionsAndLinesAreReportedWithTheirLine() {
        assertThrows(IOException.class, () -> GamepadMappings.parse(List.of("[2dc8]"), "t", "mac"));
        assertThrows(IOException.class, () -> GamepadMappings.parse(List.of("[2dc8:6009 amiga]"), "t", "mac"));
        assertThrows(IOException.class, () -> GamepadMappings.parse(List.of("[2dc8:6009"), "t", "mac"));
        assertThrows(IOException.class, () -> GamepadMappings.parse(List.of("START 7"), "t", "mac"));
        IOException e = assertThrows(IOException.class,
                () -> GamepadMappings.parse(List.of("A=0", "[2dc8:6009]", "TURBO=3"), "t", "mac"));
        assertEquals(true, e.getMessage().startsWith("t line 3:"), e.getMessage());
    }

    @Test
    void anOldFileWithoutSectionsStillWorks(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("gamepad.properties");
        Files.writeString(file, "A=1\nB=0\n");

        GamepadMapping mapping = GamepadMappings.load(file).forDevice(PADDLES);

        assertEquals(JoypadPorts.BUTTON_A, mapping.toSnesButtons(buttons(1)));
    }

    @Test
    void missingFileMeansDefaults(@TempDir Path dir) throws IOException {
        GamepadMapping mapping = GamepadMappings.load(dir.resolve("absent.properties")).forDevice(PADDLES);

        assertEquals(JoypadPorts.BUTTON_A, mapping.toSnesButtons(buttons(0)));
    }

    private static JoystickState buttons(int... numbers) {
        int bits = 0;
        for (int n : numbers) {
            bits |= 1 << n;
        }
        return new JoystickState(0, 0, bits, NO_HAT);
    }
}
