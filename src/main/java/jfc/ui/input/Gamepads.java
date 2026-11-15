package jfc.ui.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import jfc.frontend.InputSource;

/**
 * The connected gamepads, polled once per frame.
 *
 * <p>The pads found at startup play as controllers 1 and 2. If a pad is unplugged it stops
 * playing; while <i>no</i> pad is connected, a background thread looks for one every couple of
 * seconds, so plugging a pad in (or back in) works.
 *
 * <h2>Why it stops looking once a pad is connected</h2>
 * Asking winmm about an empty joystick slot makes Windows re-check its devices, which takes
 * about 160 ms, and winmm makes every other joystick call wait meanwhile, including the
 * per-frame read of the pad being played with. Looking every two seconds therefore froze the
 * game for ten frames every two seconds (measured on the user's machine). With no pad
 * connected nothing else is waiting, so the search costs nothing visible. The consequence: a
 * second pad must be plugged in before starting. (The planned LWJGL window reads pads through
 * GLFW, which is told about new devices by the system and doesn't have this problem.)
 *
 * <p>This class and {@link WinmmJoysticks} are the only code that knows how gamepads are read
 * through Windows' winmm. It's an {@link InputSource}, so the emulation session polls it the
 * same way as the keyboard.
 */
public final class Gamepads implements InputSource {

    private static final long SCAN_INTERVAL_MILLIS = 2000;
    private static final int MAX_PLAYERS = 2;

    private final WinmmJoysticks api;
    private final GamepadMapping mapping;
    /** The pads in use, in player order. Only the emulation thread touches this list. */
    private final List<WinmmJoysticks.DeviceInfo> devices = new ArrayList<>();
    /** A search result from the scan thread, waiting to be picked up by {@link #poll}. */
    private final AtomicReference<List<WinmmJoysticks.DeviceInfo>> latestScan = new AtomicReference<>();
    /** How many pads are in use; the scan thread only searches while this is 0. */
    private volatile int connectedCount;

    private Gamepads(WinmmJoysticks api, GamepadMapping mapping) {
        this.api = api;
        this.mapping = mapping;
    }

    /** Gamepad support, or empty if this platform has none (the keyboard still works). */
    public static Optional<Gamepads> open(GamepadMapping mapping) {
        Optional<WinmmJoysticks> api = WinmmJoysticks.open();
        // The scan thread gets its own instance, since each has its own native buffers.
        Optional<WinmmJoysticks> scanApi = WinmmJoysticks.open();
        if (api.isEmpty() || scanApi.isEmpty()) {
            return Optional.empty();
        }
        Gamepads gamepads = new Gamepads(api.get(), mapping);
        // Search once now (slow, but before the game starts), so pads that are already plugged
        // in work from the first frame.
        gamepads.addNewDevices(api.get().findDevices());
        gamepads.startScanThread(scanApi.get());
        return Optional.of(gamepads);
    }

    private void startScanThread(WinmmJoysticks scanApi) {
        Thread scanner = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(SCAN_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
                if (connectedCount == 0) {
                    latestScan.set(scanApi.findDevices());
                }
            }
        }, "gamepad-scan");
        scanner.setDaemon(true); // don't keep the JVM alive after the window closes
        scanner.start();
    }

    /**
     * Reads every pad, once per frame.
     *
     * @param buttonsPerPlayer filled with each player's SNES buttons (0 when no pad)
     */
    @Override
    public void poll(int[] buttonsPerPlayer) {
        List<WinmmJoysticks.DeviceInfo> scan = latestScan.getAndSet(null);
        if (scan != null) {
            addNewDevices(scan);
        }
        for (int player = 0; player < buttonsPerPlayer.length; player++) {
            buttonsPerPlayer[player] = 0;
        }
        for (int i = 0; i < devices.size(); i++) {
            JoystickState state = api.poll(devices.get(i));
            if (state == null) {
                System.out.println("Gamepad disconnected: " + devices.get(i).name());
                devices.remove(i--);
                connectedCount = devices.size();
                continue;
            }
            if (i < buttonsPerPlayer.length) {
                buttonsPerPlayer[i] = mapping.toSnesButtons(state);
            }
        }
    }

    /** Adds the pads in {@code found} that aren't in use yet, up to two players. */
    private void addNewDevices(List<WinmmJoysticks.DeviceInfo> found) {
        for (WinmmJoysticks.DeviceInfo device : found) {
            boolean known = devices.stream().anyMatch(d -> d.id() == device.id());
            if (!known && devices.size() < MAX_PLAYERS) {
                devices.add(device);
                System.out.printf("Gamepad connected as controller %d: %s (USB %04X:%04X, %d buttons)%n",
                        devices.size(), device.name(), device.vendorId(), device.productId(), device.buttonCount());
            }
        }
        connectedCount = devices.size();
    }
}
