package jfc.ui;

import jfc.ui.video.VideoMode;

/**
 * The command-line choices for playing a game in a window.
 *
 * @param videoMode         picture mode to start with (sharp pixels or an NTSC filter)
 * @param scanlines         whether NTSC modes start with scanlines
 * @param pacing              how the OpenGL window paces frames (the Swing window ignores it)
 * @param exclusiveFullscreen F11 takes over the display (exclusive fullscreen) instead of
 *                            covering it with a borderless window. Exclusive makes the monitor
 *                            re-sync for a few seconds on each switch, but lets G-Sync/FreeSync
 *                            work without the driver's "windowed mode" setting.
 * @param fullscreenRefresh   refresh rate to ask for in exclusive fullscreen, in Hz, or 0 for the
 *                            desktop's. On a 144 Hz monitor without G-Sync/FreeSync, 120 gives
 *                            perfectly even frames.
 * @param stats               print timing and audio statistics once a second
 */
public record PlayOptions(VideoMode videoMode, boolean scanlines, PacingMode pacing,
                          boolean exclusiveFullscreen, int fullscreenRefresh, boolean stats) {
}
