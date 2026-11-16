package jfc.ui.lwjgl;

import static org.lwjgl.glfw.GLFW.*;

import java.util.ArrayList;
import java.util.List;
import jfc.core.cpu.io.JoypadPorts;
import jfc.ui.InputSource;
import jfc.ui.input.GamepadMapping;
import jfc.ui.input.JoystickState;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.glfw.GLFWJoystickCallback;

/**
 * Gamepads for the OpenGL window, read through GLFW. Works the same on Windows, Linux and macOS.
 *
 * <p>Pads play as controllers 1 and 2 in the order they were plugged in. GLFW is told by the
 * system when a pad is connected or disconnected (the joystick callback, delivered while the
 * window handles its events), so there's no searching and no F9 here; the winmm reader needed
 * that because asking about empty slots stalled it for 160 ms.
 *
 * <h2>Two kinds of pad</h2>
 * <ul>
 *   <li><b>Known gamepads</b>: GLFW ships SDL's controller database, which describes the layout
 *       of hundreds of pads (Xbox, PlayStation, Switch Pro, ...) in terms of a standard pad
 *       with buttons named by position. Those map to the SNES pad by position too: the bottom
 *       face button is the SNES B, right is A, left is Y, top is X, as on the SNES's own
 *       diamond.</li>
 *   <li><b>Other joysticks</b> (the user's Buffalo SNES pad is one): their raw buttons, axes
 *       and hat go through {@link GamepadMapping}, the same mapping and
 *       {@code gamepad.properties} file the winmm reader uses.</li>
 * </ul>
 * GLFW must be used from the main thread, which is also where the OpenGL window polls input.
 */
final class GlfwGamepads implements InputSource {

    private static final int MAX_PLAYERS = 2;
    /** Stick or trigger deflection beyond which it counts as pressed. */
    private static final float THRESHOLD = 0.5f;

    private final GamepadMapping mapping;
    /** GLFW joystick ids, in player order. */
    private final List<Integer> players = new ArrayList<>();
    private final GLFWGamepadState gamepadState = GLFWGamepadState.create();
    private final GLFWJoystickCallback callback;

    /** Finds the pads already plugged in and starts listening for changes. Needs GLFW running. */
    GlfwGamepads(GamepadMapping mapping) {
        this.mapping = mapping;
        for (int jid = GLFW_JOYSTICK_1; jid <= GLFW_JOYSTICK_LAST; jid++) {
            if (glfwJoystickPresent(jid)) {
                connected(jid);
            }
        }
        // Created explicitly and kept in a field: glfwSetJoystickCallback returns the *previous*
        // callback, not this one, and this object must stay alive (and be freed at close) for
        // as long as GLFW may call it.
        callback = GLFWJoystickCallback.create((jid, event) -> {
            if (event == GLFW_CONNECTED) {
                connected(jid);
            } else if (event == GLFW_DISCONNECTED) {
                disconnected(jid);
            }
        });
        glfwSetJoystickCallback(callback);
    }

    @Override
    public void poll(int[] buttonsPerPlayer) {
        for (int player = 0; player < players.size() && player < buttonsPerPlayer.length; player++) {
            buttonsPerPlayer[player] |= read(players.get(player));
        }
    }

    /** Stops listening for pads being plugged in or out. */
    void close() {
        glfwSetJoystickCallback(null);
        callback.free();
    }

    private void connected(int jid) {
        if (players.contains(jid)) {
            return;
        }
        String how = glfwJoystickIsGamepad(jid)
                ? "standard layout, \"" + glfwGetGamepadName(jid) + "\""
                : "raw buttons through the gamepad mapping";
        if (players.size() < MAX_PLAYERS) {
            players.add(jid);
            System.out.printf("Gamepad connected as controller %d: %s (%s)%n",
                    players.size(), glfwGetJoystickName(jid).trim(), how);
        } else {
            System.out.println("Gamepad connected but not used (two players already): " + glfwGetJoystickName(jid).trim());
        }
    }

    private void disconnected(int jid) {
        int index = players.indexOf(jid);
        if (index >= 0) {
            players.remove(index);
            System.out.printf("Gamepad disconnected (was controller %d)%n", index + 1);
        }
    }

    /** One pad's SNES buttons. */
    private int read(int jid) {
        if (glfwJoystickIsGamepad(jid) && glfwGetGamepadState(jid, gamepadState)) {
            return fromStandardLayout(gamepadState);
        }
        JoystickState state = GlfwJoysticks.rawState(jid);
        return state == null ? 0 : mapping.toSnesButtons(state);
    }

    /** A known gamepad: buttons by position, D-pad or left stick for directions. */
    static int fromStandardLayout(GLFWGamepadState state) {
        int snes = 0;
        snes |= held(state, GLFW_GAMEPAD_BUTTON_A, JoypadPorts.BUTTON_B);  // bottom
        snes |= held(state, GLFW_GAMEPAD_BUTTON_B, JoypadPorts.BUTTON_A);  // right
        snes |= held(state, GLFW_GAMEPAD_BUTTON_X, JoypadPorts.BUTTON_Y);  // left
        snes |= held(state, GLFW_GAMEPAD_BUTTON_Y, JoypadPorts.BUTTON_X);  // top
        snes |= held(state, GLFW_GAMEPAD_BUTTON_LEFT_BUMPER, JoypadPorts.BUTTON_L);
        snes |= held(state, GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER, JoypadPorts.BUTTON_R);
        snes |= held(state, GLFW_GAMEPAD_BUTTON_BACK, JoypadPorts.BUTTON_SELECT);
        snes |= held(state, GLFW_GAMEPAD_BUTTON_START, JoypadPorts.BUTTON_START);
        snes |= held(state, GLFW_GAMEPAD_BUTTON_DPAD_UP, JoypadPorts.BUTTON_UP);
        snes |= held(state, GLFW_GAMEPAD_BUTTON_DPAD_DOWN, JoypadPorts.BUTTON_DOWN);
        snes |= held(state, GLFW_GAMEPAD_BUTTON_DPAD_LEFT, JoypadPorts.BUTTON_LEFT);
        snes |= held(state, GLFW_GAMEPAD_BUTTON_DPAD_RIGHT, JoypadPorts.BUTTON_RIGHT);
        // Triggers also work as L and R; they rest at -1 and go to +1.
        if (state.axes(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER) > THRESHOLD) {
            snes |= JoypadPorts.BUTTON_L;
        }
        if (state.axes(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER) > THRESHOLD) {
            snes |= JoypadPorts.BUTTON_R;
        }
        float x = state.axes(GLFW_GAMEPAD_AXIS_LEFT_X);
        float y = state.axes(GLFW_GAMEPAD_AXIS_LEFT_Y); // positive is down
        if (x < -THRESHOLD) {
            snes |= JoypadPorts.BUTTON_LEFT;
        } else if (x > THRESHOLD) {
            snes |= JoypadPorts.BUTTON_RIGHT;
        }
        if (y < -THRESHOLD) {
            snes |= JoypadPorts.BUTTON_UP;
        } else if (y > THRESHOLD) {
            snes |= JoypadPorts.BUTTON_DOWN;
        }
        return snes;
    }

    private static int held(GLFWGamepadState state, int button, int snesButton) {
        return state.buttons(button) == GLFW_PRESS ? snesButton : 0;
    }

    /** GLFW reports a hat as direction bits; JoystickState wants degrees clockwise from up. */
    static int hatDegrees(int hat) {
        return switch (hat) {
            case GLFW_HAT_UP -> 0;
            case GLFW_HAT_RIGHT_UP -> 45;
            case GLFW_HAT_RIGHT -> 90;
            case GLFW_HAT_RIGHT_DOWN -> 135;
            case GLFW_HAT_DOWN -> 180;
            case GLFW_HAT_LEFT_DOWN -> 225;
            case GLFW_HAT_LEFT -> 270;
            case GLFW_HAT_LEFT_UP -> 315;
            default -> -1; // centered
        };
    }
}
