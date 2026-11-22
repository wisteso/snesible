package snesible.ui.input;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import snesible.core.cpu.io.JoypadPorts;

/**
 * Translates a PC gamepad's state into SNES controller buttons.
 *
 * <p>PC gamepads number their buttons however their maker chose, so the face buttons need a
 * mapping. The default assumes the device numbers its buttons in the order SNES-style pads
 * usually do (A, B, X, Y, L, R, Select, Start). Lines of {@code gamepad.properties} change it
 * ({@link #set}): an SNES button name and the device button numbers {@code --gamepad-test}
 * shows, such as {@code START=7}, or {@code L=5,6,8} for a pad with more shoulder buttons (or
 * paddles) than the SNES has, any of which presses it. {@link GamepadMappings} reads the file
 * and picks the mapping for each model of pad.
 *
 * <p>The D-pad needs no mapping: it's read from the X/Y axes or the hat switch, whichever the
 * device has.
 */
public final class GamepadMapping {

    /** Axis deflection beyond which a direction counts as pressed. */
    private static final double AXIS_THRESHOLD = 0.5;

    /** SNES button names in configuration order, with their bits in the controller word. */
    private static final Map<String, Integer> SNES_BUTTONS = new LinkedHashMap<>();

    static {
        SNES_BUTTONS.put("A", JoypadPorts.BUTTON_A);
        SNES_BUTTONS.put("B", JoypadPorts.BUTTON_B);
        SNES_BUTTONS.put("X", JoypadPorts.BUTTON_X);
        SNES_BUTTONS.put("Y", JoypadPorts.BUTTON_Y);
        SNES_BUTTONS.put("L", JoypadPorts.BUTTON_L);
        SNES_BUTTONS.put("R", JoypadPorts.BUTTON_R);
        SNES_BUTTONS.put("SELECT", JoypadPorts.BUTTON_SELECT);
        SNES_BUTTONS.put("START", JoypadPorts.BUTTON_START);
    }

    /** How many device buttons a mapping covers: the 32 bits of {@link JoystickState#buttons}. */
    private static final int DEVICE_BUTTONS = 32;

    /** Device button number to SNES button bit, indexed by device button (0-31). */
    private final int[] deviceButtonToSnes = new int[DEVICE_BUTTONS];

    /** The default mapping: device buttons 0-7 are A, B, X, Y, L, R, Select, Start. */
    public GamepadMapping() {
        int deviceButton = 0;
        for (int snesButton : SNES_BUTTONS.values()) {
            deviceButtonToSnes[deviceButton++] = snesButton;
        }
    }

    /**
     * Applies one line of {@code gamepad.properties}: {@code name} is an SNES button name (any
     * case), {@code value} one device button number or several separated by commas. The SNES
     * button loses whatever device buttons it had before (its default included), so a line
     * replaces rather than adds to the mapping it's applied to.
     *
     * @param where the file and line, for error messages
     * @throws IOException if the name or a number is wrong
     */
    void set(String name, String value, String where) throws IOException {
        Integer snesButton = SNES_BUTTONS.get(name.trim().toUpperCase(Locale.ROOT));
        if (snesButton == null) {
            throw new IOException(where + ": unknown SNES button '" + name.trim()
                    + "' (the names are " + String.join(", ", SNES_BUTTONS.keySet()) + ")");
        }
        clear(snesButton);
        for (String number : value.split(",")) {
            int deviceButton;
            try {
                deviceButton = Integer.parseInt(number.trim());
            } catch (NumberFormatException e) {
                throw new IOException(where + ": '" + number.trim() + "' isn't a button number");
            }
            if (deviceButton < 0 || deviceButton >= DEVICE_BUTTONS) {
                throw new IOException(where + ": button " + deviceButton + " is out of range 0-"
                        + (DEVICE_BUTTONS - 1));
            }
            // Overwrites whatever the slot did before (another button's default, say).
            deviceButtonToSnes[deviceButton] = snesButton;
        }
    }

    /** Unmaps {@code snesButton} from every device button (its default included). */
    private void clear(int snesButton) {
        for (int i = 0; i < deviceButtonToSnes.length; i++) {
            if (deviceButtonToSnes[i] == snesButton) {
                deviceButtonToSnes[i] = 0;
            }
        }
    }

    /** The SNES buttons (as {@link JoypadPorts} bits) held according to {@code state}. */
    public int toSnesButtons(JoystickState state) {
        int snes = 0;
        for (int deviceButton = 0; deviceButton < DEVICE_BUTTONS; deviceButton++) {
            if ((state.buttons() & (1 << deviceButton)) != 0) {
                snes |= deviceButtonToSnes[deviceButton];
            }
        }
        snes |= directionsFromAxes(state.x(), state.y());
        snes |= directionsFromPov(state.pov());
        return snes;
    }

    private static int directionsFromAxes(double x, double y) {
        int directions = 0;
        if (x < -AXIS_THRESHOLD) {
            directions |= JoypadPorts.BUTTON_LEFT;
        } else if (x > AXIS_THRESHOLD) {
            directions |= JoypadPorts.BUTTON_RIGHT;
        }
        if (y < -AXIS_THRESHOLD) {
            directions |= JoypadPorts.BUTTON_UP;
        } else if (y > AXIS_THRESHOLD) {
            directions |= JoypadPorts.BUTTON_DOWN;
        }
        return directions;
    }

    /**
     * A hat switch reports an angle, clockwise from up. Diagonals (45, 135, ...) count as both
     * neighboring directions.
     */
    private static int directionsFromPov(int degrees) {
        if (degrees < 0) {
            return 0;
        }
        int directions = 0;
        if (degrees <= 45 || degrees >= 315) {
            directions |= JoypadPorts.BUTTON_UP;
        }
        if (degrees >= 45 && degrees <= 135) {
            directions |= JoypadPorts.BUTTON_RIGHT;
        }
        if (degrees >= 135 && degrees <= 225) {
            directions |= JoypadPorts.BUTTON_DOWN;
        }
        if (degrees >= 225 && degrees <= 315) {
            directions |= JoypadPorts.BUTTON_LEFT;
        }
        return directions;
    }
}
