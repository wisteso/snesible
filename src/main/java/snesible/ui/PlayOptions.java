package snesible.ui;

import snesible.core.apu.enhanced.AudioSettings;
import snesible.ui.video.VideoMode;

/**
 * The command-line choices for playing a game in a window.
 *
 * @param videoMode         picture mode to start with (sharp pixels or an NTSC filter)
 * @param scanlines         whether NTSC modes start with scanlines
 * @param pacing            how the OpenGL window paces frames (the Swing window ignores it)
 * @param fullscreenRefresh refresh rate to ask for in exclusive fullscreen (Alt+Enter), in Hz,
 *                          or 0 for the desktop's. On a 144 Hz monitor without
 *                          G-Sync/FreeSync, 120 gives perfectly even frames.
 * @param stats             print timing and audio statistics once a second
 * @param audio             the sound preset to start with (F10 cycles it) and the stereo options
 */
public record PlayOptions(VideoMode videoMode, boolean scanlines, PacingMode pacing,
                          int fullscreenRefresh, boolean stats, AudioSettings audio) {
}
