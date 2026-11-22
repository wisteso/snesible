package snesible.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import snesible.core.cpu.io.JoypadPorts;
import org.junit.jupiter.api.Test;

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
    void aLineReplacesTheButtonsDefault() throws IOException {
        GamepadMapping mapping = new GamepadMapping();
        mapping.set("a", "1", "test");

        assertEquals(JoypadPorts.BUTTON_A, mapping.toSnesButtons(buttons(1)));
        assertEquals(0, mapping.toSnesButtons(buttons(0)), "A's default button is unmapped");
        assertEquals(JoypadPorts.BUTTON_START, mapping.toSnesButtons(buttons(7)), "others unchanged");
    }

    @Test
    void badLinesAreReported() {
        GamepadMapping mapping = new GamepadMapping();
        assertThrows(IOException.class, () -> mapping.set("TURBO", "3", "test"));
        assertThrows(IOException.class, () -> mapping.set("L", "5,x", "test"));
        assertThrows(IOException.class, () -> mapping.set("L", "32", "test"));
    }

    private static JoystickState buttons(int... numbers) {
        int bits = 0;
        for (int n : numbers) {
            bits |= 1 << n;
        }
        return new JoystickState(0, 0, bits, NO_HAT);
    }
}
