package jfc.ui.input.winmm;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import jfc.ui.InputSource;
import jfc.ui.input.GamepadMapping;
import jfc.ui.input.JoystickState;

/**
 * The connected gamepads, polled once per frame.
 *
 * <p>The pads found at startup play as controllers 1 and 2. If a pad is unplugged it stops
 * playing. New pads are only looked for when the user asks ({@link #requestRescan}, F9 in the
 * window), as Snes9x does.
 *
 * <h2>Why not look for new pads automatically?</h2>
 * Asking winmm about an empty joystick slot makes Windows re-check its devices, which takes
 * about 160 ms, and winmm makes every other joystick call wait meanwhile, including the
 * per-frame read of the pad being played with. JFC used to look every two seconds, which froze
 * the game for ten frames every two seconds (measured on the user's machine). Many SNES games
 * are timing-sensitive, so the search only happens on request: one deliberate pause instead of
 * a regular one. (Milestone 10f will read pads through GLFW in the OpenGL window; GLFW is
 * told about new devices by the system.)
 *
 * <p>This class and {@link WinmmJoysticks} are the only code that knows how gamepads are read
 * through Windows' winmm. It's an {@link InputSource}, so the emulation session polls it the
 * same way as the keyboard.
 */
public final class Gamepads implements InputSource {

    private static final int MAX_PLAYERS = 2;

    private final WinmmJoysticks api;
    private final GamepadMapping mapping;
    /** The pads in use, in player order. Only the emulation thread touches this list. */
    private final List<WinmmJoysticks.DeviceInfo> devices = new ArrayList<>();
    /** Set by the user interface; the next {@link #poll} looks for new pads. */
    private volatile boolean rescanRequested;

    private Gamepads(WinmmJoysticks api, GamepadMapping mapping) {
        this.api = api;
        this.mapping = mapping;
    }

    /**
     * Gamepad support, or empty if this platform has none (the keyboard still works). Looks for
     * pads right away (slow, but before the game starts), so pads that are already plugged in
     * work from the first frame.
     */
    public static Optional<Gamepads> open(GamepadMapping mapping) {
        return WinmmJoysticks.open().map(api -> {
            Gamepads gamepads = new Gamepads(api, mapping);
            gamepads.rescan();
            return gamepads;
        });
    }

    /**
     * Asks for a search for newly plugged-in pads. Safe to call from any thread; the search
     * runs at the start of the next frame and pauses the game for a moment.
     */
    public void requestRescan() {
        rescanRequested = true;
    }

    /**
     * Reads every pad, once per frame.
     *
     * @param buttonsPerPlayer filled with each player's SNES buttons (0 when no pad)
     */
    @Override
    public void poll(int[] buttonsPerPlayer) {
        if (rescanRequested) {
            rescanRequested = false;
            rescan();
        }
        for (int player = 0; player < buttonsPerPlayer.length; player++) {
            buttonsPerPlayer[player] = 0;
        }
        for (int i = 0; i < devices.size(); i++) {
            JoystickState state = api.poll(devices.get(i));
            if (state == null) {
                System.out.println("Gamepad disconnected: " + devices.get(i).name()
                        + " (press F9 to look for pads again after reconnecting)");
                devices.remove(i--);
                continue;
            }
            if (i < buttonsPerPlayer.length) {
                buttonsPerPlayer[i] = mapping.toSnesButtons(state);
            }
        }
    }

    /** Looks for pads not yet in use and adds them, up to two players. */
    private void rescan() {
        int before = devices.size();
        for (WinmmJoysticks.DeviceInfo found : api.findDevices()) {
            boolean known = devices.stream().anyMatch(d -> d.id() == found.id());
            if (!known && devices.size() < MAX_PLAYERS) {
                devices.add(found);
                System.out.printf("Gamepad connected as controller %d: %s (USB %04X:%04X, %d buttons)%n",
                        devices.size(), found.name(), found.vendorId(), found.productId(), found.buttonCount());
            }
        }
        if (devices.size() == before) {
            System.out.println(devices.isEmpty() ? "No gamepads found." : "No new gamepads found.");
        }
    }
}
