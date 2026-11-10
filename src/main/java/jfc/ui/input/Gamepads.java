package jfc.ui.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The connected gamepads, polled once per frame.
 *
 * <p>The first pad found plays as controller 1, the second as controller 2. Pads can be plugged
 * in or out while the emulator runs: when fewer than two are connected, it looks for new ones
 * every couple of seconds.
 *
 * <p>This class and {@link WinmmJoysticks} are the only code that knows how gamepads are read.
 * Switching to another input library means replacing them, nothing else.
 */
public final class Gamepads {

    private static final int RESCAN_INTERVAL_FRAMES = 120;
    private static final int MAX_PLAYERS = 2;

    private final WinmmJoysticks api;
    private final GamepadMapping mapping;
    private final List<WinmmJoysticks.DeviceInfo> devices = new ArrayList<>();
    private int framesUntilRescan;

    private Gamepads(WinmmJoysticks api, GamepadMapping mapping) {
        this.api = api;
        this.mapping = mapping;
    }

    /** Gamepad support, or empty if this platform has none (the keyboard still works). */
    public static Optional<Gamepads> open(GamepadMapping mapping) {
        return WinmmJoysticks.open().map(api -> new Gamepads(api, mapping));
    }

    /**
     * Reads every pad, once per frame.
     *
     * @param buttonsPerPlayer filled with each player's SNES buttons (0 when no pad)
     */
    public void poll(int[] buttonsPerPlayer) {
        if (devices.size() < MAX_PLAYERS && --framesUntilRescan <= 0) {
            rescan();
        }
        for (int player = 0; player < buttonsPerPlayer.length; player++) {
            buttonsPerPlayer[player] = 0;
        }
        for (int i = 0; i < devices.size(); i++) {
            JoystickState state = api.poll(devices.get(i));
            if (state == null) {
                System.out.println("Gamepad disconnected: " + devices.get(i).name());
                devices.remove(i--);
                continue;
            }
            if (i < buttonsPerPlayer.length) {
                buttonsPerPlayer[i] = mapping.toSnesButtons(state);
            }
        }
    }

    private void rescan() {
        framesUntilRescan = RESCAN_INTERVAL_FRAMES;
        for (WinmmJoysticks.DeviceInfo found : api.findDevices()) {
            boolean known = devices.stream().anyMatch(d -> d.id() == found.id());
            if (!known && devices.size() < MAX_PLAYERS) {
                devices.add(found);
                System.out.printf("Gamepad connected as controller %d: %s (USB %04X:%04X, %d buttons)%n",
                        devices.size(), found.name(), found.vendorId(), found.productId(), found.buttonCount());
            }
        }
    }
}
