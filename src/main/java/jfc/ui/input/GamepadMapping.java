package jfc.ui.input;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import jfc.cpu.io.JoypadPorts;

/**
 * Translates a PC gamepad's state into SNES controller buttons.
 *
 * <p>PC gamepads number their buttons however their maker chose, so the face buttons need a
 * mapping. The default assumes the device numbers its buttons in the order SNES-style pads
 * usually do (A, B, X, Y, L, R, Select, Start). Different pads can be configured with a
 * properties file of SNES button names and device button numbers, as
 * {@code --gamepad-test} shows them:
 * <pre>
 *   A=0
 *   B=1
 *   START=7
 * </pre>
 * The D-pad needs no mapping: it's read from the X/Y axes or the hat switch, whichever the
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

    /** Device button number to SNES button bit, indexed by device button (0-31). */
    private final int[] deviceButtonToSnes = new int[32];

    /** The default mapping: device buttons 0-7 are A, B, X, Y, L, R, Select, Start. */
    public GamepadMapping() {
        int deviceButton = 0;
        for (int snesButton : SNES_BUTTONS.values()) {
            deviceButtonToSnes[deviceButton++] = snesButton;
        }
    }

    /**
     * The default mapping with overrides from {@code file}, if it exists. Each line maps an SNES
     * button name to a device button number.
     */
    public static GamepadMapping load(Path file) throws IOException {
        GamepadMapping mapping = new GamepadMapping();
        if (!Files.isRegularFile(file)) {
            return mapping;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        for (String name : properties.stringPropertyNames()) {
            Integer snesButton = SNES_BUTTONS.get(name.trim().toUpperCase());
            if (snesButton == null) {
                throw new IOException(file + ": unknown SNES button '" + name + "'");
            }
            int deviceButton = Integer.parseInt(properties.getProperty(name).trim());
            mapping.assign(snesButton, deviceButton);
        }
        return mapping;
    }

    /** Makes {@code deviceButton} produce {@code snesButton}, replacing earlier assignments. */
    void assign(int snesButton, int deviceButton) {
        for (int i = 0; i < deviceButtonToSnes.length; i++) {
            if (deviceButtonToSnes[i] == snesButton) {
                deviceButtonToSnes[i] = 0;
            }
        }
        deviceButtonToSnes[deviceButton] = snesButton;
    }

    /** The SNES buttons (as {@link JoypadPorts} bits) held according to {@code state}. */
    public int toSnesButtons(JoystickState state) {
        int snes = 0;
        for (int deviceButton = 0; deviceButton < 32; deviceButton++) {
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
