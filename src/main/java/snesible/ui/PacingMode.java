package snesible.ui;

import java.util.Locale;

/**
 * How a window that can synchronize with the display decides when to run each frame. (The
 * Swing window can't, so it always uses a steady clock.)
 *
 * <p>Both real modes keep vsync on, so the picture never tears. The difference is who sets the
 * pace:
 * <ul>
 *   <li>{@link #VSYNC}: the display. One frame every 1, 2 or 4 refreshes, so every frame is on
 *       screen for exactly the same time. Only sensible when the refresh rate is close to a
 *       multiple of 60 Hz; the game then runs at the display's rate (60.00 instead of 60.10
 *       frames per second, which nobody can notice).</li>
 *   <li>{@link #CLOCK}: a steady clock at the console's own rate, each frame shown as soon as
 *       it's drawn. On a variable-refresh monitor (G-Sync, FreeSync) the display waits for
 *       each frame, so this is smooth at exact speed. On a fixed-refresh monitor that isn't a
 *       multiple of 60 Hz (144 Hz, say), frames alternate between being shown for 2 and 3
 *       refreshes, a slight judder.</li>
 *   <li>{@link #AUTO}: {@code VSYNC} when the refresh rate is within 0.5% of a multiple of the
 *       console's rate, {@code CLOCK} otherwise.</li>
 * </ul>
 * In every mode the sound adapts with dynamic rate control (see
 * {@link snesible.ui.audio.AudioOutput}), so it never sets the pace.
 */
public enum PacingMode {
    AUTO, VSYNC, CLOCK;

    /** Parses a command-line name: {@code auto}, {@code vsync} or {@code clock}. */
    public static PacingMode parse(String name) {
        return valueOf(name.toUpperCase(Locale.ROOT));
    }
}
