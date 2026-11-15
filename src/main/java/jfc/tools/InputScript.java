package jfc.tools;

import java.util.HashMap;
import java.util.Map;
import jfc.core.cpu.io.JoypadPorts;

/**
 * Scripted controller input for headless runs: entries like {@code START@200} hold Start for a
 * few frames from frame 200. It lets tests and screenshots get past title screens.
 */
final class InputScript {

    /** How many frames a scripted button press is held for. */
    private static final int PRESS_FRAMES = 6;

    private static final Map<String, Integer> BUTTONS = Map.ofEntries(
            Map.entry("B", JoypadPorts.BUTTON_B), Map.entry("Y", JoypadPorts.BUTTON_Y),
            Map.entry("SELECT", JoypadPorts.BUTTON_SELECT), Map.entry("START", JoypadPorts.BUTTON_START),
            Map.entry("UP", JoypadPorts.BUTTON_UP), Map.entry("DOWN", JoypadPorts.BUTTON_DOWN),
            Map.entry("LEFT", JoypadPorts.BUTTON_LEFT), Map.entry("RIGHT", JoypadPorts.BUTTON_RIGHT),
            Map.entry("A", JoypadPorts.BUTTON_A), Map.entry("X", JoypadPorts.BUTTON_X),
            Map.entry("L", JoypadPorts.BUTTON_L), Map.entry("R", JoypadPorts.BUTTON_R));

    /** Frame number where a press starts, mapped to the buttons pressed. */
    private final Map<Integer, Integer> pressStarts = new HashMap<>();

    InputScript(String[] presses) {
        for (String press : presses) {
            String[] parts = press.split("@");
            Integer button = parts.length == 2 ? BUTTONS.get(parts[0].toUpperCase()) : null;
            if (button == null) {
                throw new IllegalArgumentException("Expected BUTTON@frame, got " + press);
            }
            pressStarts.merge(Integer.parseInt(parts[1]), button, (a, b) -> a | b);
        }
    }

    /** The buttons held during {@code frame} (counting from 1). */
    int buttonsAt(int frame) {
        int held = 0;
        for (Map.Entry<Integer, Integer> entry : pressStarts.entrySet()) {
            if (frame >= entry.getKey() && frame < entry.getKey() + PRESS_FRAMES) {
                held |= entry.getValue();
            }
        }
        return held;
    }
}
