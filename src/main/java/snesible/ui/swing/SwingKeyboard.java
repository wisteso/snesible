package snesible.ui.swing;

import java.awt.event.KeyEvent;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import snesible.core.cpu.io.JoypadPorts;
import snesible.ui.InputSource;

/**
 * The keyboard as controller 1, for the Swing window.
 *
 * <p>Swing's event thread reports key presses and releases through {@link #handle}; the
 * emulation thread reads the held buttons when it {@link #poll polls}. The two share only an
 * atomic integer holding one bit per SNES button.
 *
 * <p>Keys: arrows = D-pad, X = A, Z = B, S = X, A = Y, Q = L, W = R, Enter = Start,
 * Shift or Backspace = Select.
 */
public final class SwingKeyboard implements InputSource {

    private static final Map<Integer, Integer> KEY_BINDINGS = Map.ofEntries(
            Map.entry(KeyEvent.VK_UP, JoypadPorts.BUTTON_UP),
            Map.entry(KeyEvent.VK_DOWN, JoypadPorts.BUTTON_DOWN),
            Map.entry(KeyEvent.VK_LEFT, JoypadPorts.BUTTON_LEFT),
            Map.entry(KeyEvent.VK_RIGHT, JoypadPorts.BUTTON_RIGHT),
            Map.entry(KeyEvent.VK_X, JoypadPorts.BUTTON_A),
            Map.entry(KeyEvent.VK_Z, JoypadPorts.BUTTON_B),
            Map.entry(KeyEvent.VK_S, JoypadPorts.BUTTON_X),
            Map.entry(KeyEvent.VK_A, JoypadPorts.BUTTON_Y),
            Map.entry(KeyEvent.VK_Q, JoypadPorts.BUTTON_L),
            Map.entry(KeyEvent.VK_W, JoypadPorts.BUTTON_R),
            Map.entry(KeyEvent.VK_ENTER, JoypadPorts.BUTTON_START),
            Map.entry(KeyEvent.VK_SHIFT, JoypadPorts.BUTTON_SELECT),
            Map.entry(KeyEvent.VK_BACK_SPACE, JoypadPorts.BUTTON_SELECT));

    private final AtomicInteger heldButtons = new AtomicInteger();

    /**
     * Records a key press or release, on Swing's event thread.
     *
     * @return true if the key is a controller button (so Swing shouldn't handle it further)
     */
    boolean handle(KeyEvent e) {
        Integer button = KEY_BINDINGS.get(e.getKeyCode());
        if (button == null) {
            return false;
        }
        if (e.getID() == KeyEvent.KEY_PRESSED) {
            heldButtons.updateAndGet(held -> held | button);
        } else if (e.getID() == KeyEvent.KEY_RELEASED) {
            heldButtons.updateAndGet(held -> held & ~button);
        }
        return true;
    }

    @Override
    public void poll(int[] buttonsPerPlayer) {
        buttonsPerPlayer[0] |= heldButtons.get();
    }
}
