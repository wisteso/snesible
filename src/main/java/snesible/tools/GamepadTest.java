package snesible.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import snesible.core.cpu.io.JoypadPorts;
import snesible.ui.input.GamepadMapping;
import snesible.ui.input.JoystickState;
import snesible.ui.input.winmm.WinmmJoysticks;
import snesible.ui.lwjgl.GlfwJoysticks;

/**
 * {@code --gamepad-test}: lists connected gamepads and prints every button press, so the right
 * button numbers can be put into {@code gamepad.properties}.
 *
 * <p>The two windows read pads differently, and a pad's button numbers can differ between the
 * two, so the test reads them the way the chosen window does:
 * <ul>
 *   <li>By default through <b>GLFW</b>, like the OpenGL window. A pad GLFW knows (Xbox,
 *       PlayStation, ...) is mapped by position and ignores {@code gamepad.properties}; the
 *       test says so and shows the SNES buttons that result.</li>
 *   <li>With {@code --swing}, through Windows' <b>winmm</b>, like the Swing window.</li>
 * </ul>
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

    /**
     * @param winmm read pads through winmm (the Swing window's way) instead of GLFW
     */
    public static void run(GamepadMapping mapping, boolean winmm) throws InterruptedException {
        if (winmm) {
            runWinmm(mapping);
            return;
        }
        GlfwJoysticks glfw;
        try {
            glfw = GlfwJoysticks.open().orElse(null);
        } catch (LinkageError e) {
            // Run with "java -cp target/classes" instead of "java -jar": no target/lib.
            System.out.println("LWJGL isn't on the class path (use java -jar); add --swing to test through winmm.");
            return;
        }
        if (glfw == null) {
            System.out.println("GLFW couldn't start; add --swing to test through winmm.");
            return;
        }
        try (glfw) {
            runGlfw(glfw, mapping);
        }
    }

    private static void runGlfw(GlfwJoysticks glfw, GamepadMapping mapping) throws InterruptedException {
        System.out.println("Reading gamepads through GLFW, as the OpenGL window does (--swing: as the Swing window does).");
        List<Integer> ids = glfw.connected();
        if (ids.isEmpty()) {
            System.out.println("No gamepads found. Plug one in and try again.");
            return;
        }
        for (int jid : ids) {
            System.out.println(glfw.describe(jid));
        }
        int jid = ids.get(0);
        boolean standard = glfw.isGamepad(jid);
        System.out.println();
        System.out.println("Testing joystick " + jid + ". Press buttons; Ctrl+C to stop.");
        if (standard) {
            System.out.println("This pad is mapped by position (bottom face button = SNES B, right = A, left = Y, top = X);");
            System.out.println("gamepad.properties doesn't apply to it. Device button numbers are shown for reference.");
        } else {
            System.out.println("Device button numbers are what gamepad.properties uses, e.g. START=7.");
        }
        watch(() -> {
            glfw.pollEvents();
            return GlfwJoysticks.rawState(jid);
        }, state -> standard ? glfw.standardLayoutButtons(jid) : mapping.toSnesButtons(state));
    }

    private static void runWinmm(GamepadMapping mapping) throws InterruptedException {
        System.out.println("Reading gamepads through winmm, as the Swing window does (without --swing: as the OpenGL window does).");
        WinmmJoysticks api = WinmmJoysticks.open().orElse(null);
        if (api == null) {
            System.out.println("winmm is only available on Windows.");
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
        watch(() -> api.poll(device), mapping::toSnesButtons);
    }

    /**
     * Prints the pad's state whenever it changes, until it's unplugged.
     *
     * @param read     the pad's current raw state, or null once it's unplugged
     * @param toSnes   the SNES buttons the window would see for a state
     */
    private static void watch(Supplier<JoystickState> read, ToIntFunction<JoystickState> toSnes)
            throws InterruptedException {
        JoystickState previous = null;
        while (true) {
            JoystickState state = read.get();
            if (state == null) {
                System.out.println("Disconnected.");
                return;
            }
            if (previous == null || hasChanged(previous, state)) {
                System.out.printf("device buttons %-14s  x=%5.2f y=%5.2f%s  ->  SNES %s%n",
                        buttonNumbers(state.buttons()), state.x(), state.y(),
                        state.pov() >= 0 ? " hat=" + state.pov() : "",
                        snesNames(toSnes.applyAsInt(state)));
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
