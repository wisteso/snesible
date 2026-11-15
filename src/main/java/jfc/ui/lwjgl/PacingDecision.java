package jfc.ui.lwjgl;

import jfc.ui.PacingMode;

/**
 * The pacing the OpenGL window settles on for a display, worked out from the measured refresh
 * rate. A pure calculation, kept separate from the window so it can be tested without one.
 *
 * @param mode              {@link PacingMode#VSYNC} or {@link PacingMode#CLOCK}, never AUTO
 * @param refreshesPerFrame with VSYNC, how many refreshes each frame stays on screen
 * @param vsyncWorks        false when swaps didn't wait for the display (vsync forced off in
 *                          the driver, or a refresh rate that makes no sense)
 * @param refusedVsync      true when vsync was asked for but would have run the game at the
 *                          wrong speed, so the clock is used instead
 */
record PacingDecision(PacingMode mode, int refreshesPerFrame, boolean vsyncWorks, boolean refusedVsync) {

    /** In AUTO mode, lock to the display when its rate is this close to a multiple of the console's. */
    static final double AUTO_TOLERANCE = 0.005;
    /** Asking for vsync explicitly accepts a looser match, but never a noticeably wrong speed. */
    static final double FORCED_TOLERANCE = 0.01;

    /**
     * @param measuredRefresh the display's refresh rate in Hz, as measured by timing swaps
     * @param fps             the console's frame rate (60.10 for NTSC)
     * @param requested       the {@code --pacing} option
     */
    static PacingDecision choose(double measuredRefresh, double fps, PacingMode requested) {
        boolean vsyncWorks = measuredRefresh > 20 && measuredRefresh < 500;
        int multiple = (int) Math.round(measuredRefresh / fps);
        double speedError = multiple >= 1 ? Math.abs(measuredRefresh / multiple - fps) / fps : 1;
        boolean lockable = vsyncWorks && multiple >= 1;
        PacingMode mode = switch (requested) {
            case AUTO -> lockable && speedError < AUTO_TOLERANCE ? PacingMode.VSYNC : PacingMode.CLOCK;
            // Locking a 144 Hz display to every second refresh would run the game at 72 fps,
            // 20% too fast, so even a forced VSYNC falls back to the clock in that case.
            case VSYNC -> lockable && speedError < FORCED_TOLERANCE ? PacingMode.VSYNC : PacingMode.CLOCK;
            case CLOCK -> PacingMode.CLOCK;
        };
        boolean refused = requested == PacingMode.VSYNC && mode != PacingMode.VSYNC;
        return new PacingDecision(mode, Math.max(1, multiple), vsyncWorks, refused);
    }
}
