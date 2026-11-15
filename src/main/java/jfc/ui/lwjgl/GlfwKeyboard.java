package jfc.ui.lwjgl;

import static org.lwjgl.glfw.GLFW.*;

import java.util.Map;
import jfc.core.cpu.io.JoypadPorts;
import jfc.ui.InputSource;

/**
 * The keyboard as controller 1, for the OpenGL (GLFW) window.
 *
 * <p>GLFW reports key presses and releases to {@link #handle} while the window processes its
 * events; the session reads the held buttons when it {@link #poll polls}. Both happen on the
 * main thread, between frames, so no synchronization is needed.
 *
 * <p>GLFW names keys by their position on a US keyboard, so on other layouts the controller
 * keys stay in the same place. The mapping matches the Swing window's (see
 * {@link jfc.ui.Controls}).
 */
final class GlfwKeyboard implements InputSource {

    private static final Map<Integer, Integer> KEY_BINDINGS = Map.ofEntries(
            Map.entry(GLFW_KEY_UP, JoypadPorts.BUTTON_UP),
            Map.entry(GLFW_KEY_DOWN, JoypadPorts.BUTTON_DOWN),
            Map.entry(GLFW_KEY_LEFT, JoypadPorts.BUTTON_LEFT),
            Map.entry(GLFW_KEY_RIGHT, JoypadPorts.BUTTON_RIGHT),
            Map.entry(GLFW_KEY_X, JoypadPorts.BUTTON_A),
            Map.entry(GLFW_KEY_Z, JoypadPorts.BUTTON_B),
            Map.entry(GLFW_KEY_S, JoypadPorts.BUTTON_X),
            Map.entry(GLFW_KEY_A, JoypadPorts.BUTTON_Y),
            Map.entry(GLFW_KEY_Q, JoypadPorts.BUTTON_L),
            Map.entry(GLFW_KEY_W, JoypadPorts.BUTTON_R),
            Map.entry(GLFW_KEY_ENTER, JoypadPorts.BUTTON_START),
            Map.entry(GLFW_KEY_LEFT_SHIFT, JoypadPorts.BUTTON_SELECT),
            Map.entry(GLFW_KEY_RIGHT_SHIFT, JoypadPorts.BUTTON_SELECT),
            Map.entry(GLFW_KEY_BACKSPACE, JoypadPorts.BUTTON_SELECT));

    private int heldButtons;

    /**
     * Records a key event from GLFW's key callback.
     *
     * @param action {@code GLFW_PRESS}, {@code GLFW_RELEASE} or {@code GLFW_REPEAT}
     * @return true if the key is a controller button
     */
    boolean handle(int key, int action) {
        Integer button = KEY_BINDINGS.get(key);
        if (button == null) {
            return false;
        }
        if (action == GLFW_PRESS) {
            heldButtons |= button;
        } else if (action == GLFW_RELEASE) {
            heldButtons &= ~button;
        }
        return true;
    }

    /** Lets go of every button (when the window loses focus, releases would go unseen). */
    void releaseAll() {
        heldButtons = 0;
    }

    @Override
    public void poll(int[] buttonsPerPlayer) {
        buttonsPerPlayer[0] |= heldButtons;
    }
}
