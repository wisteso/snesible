package jfc.ui;

/**
 * Something that presses controller buttons: a keyboard, gamepads, a scripted test.
 *
 * <p>{@link EmulationSession} polls every source once per frame, just before running it, and
 * combines them: a button counts as held if any source holds it. That's how the keyboard and the
 * first gamepad can both play as controller 1.
 */
public interface InputSource {

    /**
     * Reports which buttons are held right now, called on the emulation thread.
     *
     * @param buttonsPerPlayer one entry per controller port, already cleared to 0. Set the bits
     *                         from {@link jfc.core.cpu.io.JoypadPorts} ({@code BUTTON_A} etc.) for the
     *                         buttons this source holds.
     */
    void poll(int[] buttonsPerPlayer);
}
