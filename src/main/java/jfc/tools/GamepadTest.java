package jfc.tools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jfc.core.cpu.io.JoypadPorts;
import jfc.ui.input.GamepadMapping;
import jfc.ui.input.JoystickState;
import jfc.ui.input.winmm.WinmmJoysticks;

/**
 * {@code --gamepad-test}: lists connected gamepads and prints every button press, so the right
 * button numbers can be put into {@code gamepad.properties}.
 */
public final class GamepadTest {

    private static final Map<Integer, String> SNES_NAMES = Map.ofEntries(
            Map.entry(JoypadPorts.BUTTON_B, "B"), Map.entry(JoypadPorts.BUTTON_Y, "Y"),
            Map.entry(JoypadPorts.BUTTON_SELECT, "Select"), Map.entry(JoypadPorts.BUTTON_START, "Start"),
            Map.entry(JoypadPorts.BUTTON_UP, "Up"), Map.entry(JoypadPorts.BUTTON_DOWN, "Down"),
            Map.entry(JoypadPorts.BUTTON_LEFT, "Left"), Map.entry(JoypadPorts.BUTTON_RIGHT, "Right"),
            Map.entry(JoypadPorts.BUTTON_A, "A"), Map.entry(JoypadPorts.BUTTON_X, "X"),
            Map.entry(JoypadPorts.BUTTON_L, "L"), Map.entry(JoypadPorts.BUTTON_R, "R"));

    private GamepadTest() {
    }

    public static void run(GamepadMapping mapping) throws IOException, InterruptedException {
        WinmmJoysticks api = WinmmJoysticks.open().orElse(null);
        if (api == null) {
            System.out.println("Gamepads are only supported on Windows for now.");
            return;
        }
        List<WinmmJoysticks.DeviceInfo> devices = api.findDevices();
        if (devices.isEmpty()) {
            System.out.println("No gamepads found. Plug one in and try again.");
            return;
        }
        for (WinmmJoysticks.DeviceInfo d : devices) {
            System.out.printf("Joystick %d: %s  USB %04X:%04X  %d buttons%s%n", d.id(), d.name(),
                    d.vendorId(), d.productId(), d.buttonCount(), d.hasPov() ? ", hat switch" : "");
        }
        WinmmJoysticks.DeviceInfo device = devices.get(0);
        System.out.println();
        System.out.println("Testing joystick " + device.id() + ". Press buttons; Ctrl+C to stop.");
        System.out.println("Device button numbers are what gamepad.properties uses, e.g. START=7.");

        JoystickState previous = null;
        while (true) {
            JoystickState state = api.poll(device);
            if (state == null) {
                System.out.println("Disconnected.");
                return;
            }
            if (previous == null || hasChanged(previous, state)) {
                System.out.printf("device buttons %-14s  x=%5.2f y=%5.2f%s  ->  SNES %s%n",
                        buttonNumbers(state.buttons()), state.x(), state.y(),
                        state.pov() >= 0 ? " hat=" + state.pov() : "",
                        snesNames(mapping.toSnesButtons(state)));
                previous = state;
            }
            Thread.sleep(16);
        }
    }

    /** Ignores tiny analog wobble so only real changes are printed. */
    private static boolean hasChanged(JoystickState a, JoystickState b) {
        return a.buttons() != b.buttons() || a.pov() != b.pov()
                || Math.abs(a.x() - b.x()) > 0.25 || Math.abs(a.y() - b.y()) > 0.25;
    }

    private static String buttonNumbers(int buttons) {
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            if ((buttons & (1 << i)) != 0) {
                numbers.add(String.valueOf(i));
            }
        }
        return numbers.isEmpty() ? "(none)" : String.join(" ", numbers);
    }

    private static String snesNames(int snesButtons) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : SNES_NAMES.entrySet()) {
            if ((snesButtons & entry.getKey()) != 0) {
                names.add(entry.getValue());
            }
        }
        return names.isEmpty() ? "(none)" : String.join(" ", names);
    }
}
