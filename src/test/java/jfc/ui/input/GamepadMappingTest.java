package jfc.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import jfc.cpu.io.JoypadPorts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GamepadMappingTest {

    private static final int NO_HAT = -1;

    private final GamepadMapping defaults = new GamepadMapping();

    @Test
    void defaultMappingFollowsSnesStylePadOrder() {
        assertEquals(JoypadPorts.BUTTON_A, defaults.toSnesButtons(buttons(0)));
        assertEquals(JoypadPorts.BUTTON_B, defaults.toSnesButtons(buttons(1)));
        assertEquals(JoypadPorts.BUTTON_START, defaults.toSnesButtons(buttons(7)));
        assertEquals(JoypadPorts.BUTTON_L | JoypadPorts.BUTTON_R, defaults.toSnesButtons(buttons(4, 5)));
    }

    @Test
    void axesBeyondHalfwayPressTheDpad() {
        assertEquals(JoypadPorts.BUTTON_LEFT | JoypadPorts.BUTTON_DOWN,
                defaults.toSnesButtons(new JoystickState(-1.0, 1.0, 0, NO_HAT)));
        assertEquals(0, defaults.toSnesButtons(new JoystickState(0.3, -0.3, 0, NO_HAT)));
    }

    @Test
    void hatDiagonalsPressBothDirections() {
        assertEquals(JoypadPorts.BUTTON_UP | JoypadPorts.BUTTON_RIGHT,
                defaults.toSnesButtons(new JoystickState(0, 0, 0, 45)));
        assertEquals(JoypadPorts.BUTTON_LEFT, defaults.toSnesButtons(new JoystickState(0, 0, 0, 270)));
    }

    @Test
    void propertiesFileOverridesDefaults(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("gamepad.properties");
        Files.writeString(file, "A=1\nB=0\n");

        GamepadMapping mapping = GamepadMapping.load(file);

        assertEquals(JoypadPorts.BUTTON_A, mapping.toSnesButtons(buttons(1)));
        assertEquals(JoypadPorts.BUTTON_B, mapping.toSnesButtons(buttons(0)));
        assertEquals(JoypadPorts.BUTTON_START, mapping.toSnesButtons(buttons(7)), "others unchanged");
    }

    @Test
    void unknownButtonNameIsReported(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("gamepad.properties");
        Files.writeString(file, "TURBO=3\n");

        assertThrows(IOException.class, () -> GamepadMapping.load(file));
    }

    @Test
    void missingFileMeansDefaults(@TempDir Path dir) throws IOException {
        GamepadMapping mapping = GamepadMapping.load(dir.resolve("absent.properties"));

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
