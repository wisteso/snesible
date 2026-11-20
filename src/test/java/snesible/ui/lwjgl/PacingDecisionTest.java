package snesible.ui.lwjgl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import snesible.ui.PacingMode;
import org.junit.jupiter.api.Test;

/** Which pacing the OpenGL window picks for common displays. */
class PacingDecisionTest {

    private static final double NTSC = 21_477_272.0 / (1364 * 262); // 60.10 fps
    private static final double PAL = 21_281_370.0 / (1364 * 312);  // 50.01 fps

    @Test
    void sixtyHertzLocksOneFramePerRefresh() {
        PacingDecision d = PacingDecision.choose(60.0, NTSC, PacingMode.AUTO);
        assertEquals(PacingMode.VSYNC, d.mode());
        assertEquals(1, d.refreshesPerFrame());
    }

    @Test
    void ntscTelevisionRateLocksToo() {
        // 59.94 Hz is 0.27% slower than the console: close enough to lock.
        assertEquals(PacingMode.VSYNC, PacingDecision.choose(59.94, NTSC, PacingMode.AUTO).mode());
    }

    @Test
    void oneHundredTwentyHertzLocksEveryOtherRefresh() {
        PacingDecision d = PacingDecision.choose(119.98, NTSC, PacingMode.AUTO);
        assertEquals(PacingMode.VSYNC, d.mode());
        assertEquals(2, d.refreshesPerFrame());
    }

    @Test
    void oneHundredFortyFourHertzUsesTheClock() {
        assertEquals(PacingMode.CLOCK, PacingDecision.choose(143.9, NTSC, PacingMode.AUTO).mode());
    }

    @Test
    void forcingVsyncOnOneFortyFourIsRefusedRatherThanRunningTwentyPercentFast() {
        PacingDecision d = PacingDecision.choose(144.0, NTSC, PacingMode.VSYNC);
        assertEquals(PacingMode.CLOCK, d.mode());
        assertTrue(d.refusedVsync());
    }

    @Test
    void brokenVsyncFallsBackToTheClock() {
        // Swaps that don't wait for the display come back thousands of times a second.
        PacingDecision d = PacingDecision.choose(3000, NTSC, PacingMode.AUTO);
        assertEquals(PacingMode.CLOCK, d.mode());
        assertFalse(d.vsyncWorks());
    }

    @Test
    void palGamesLockToFiftyHertzButNotSixty() {
        assertEquals(PacingMode.VSYNC, PacingDecision.choose(50.0, PAL, PacingMode.AUTO).mode());
        assertEquals(PacingMode.CLOCK, PacingDecision.choose(60.0, PAL, PacingMode.AUTO).mode());
    }

    @Test
    void forcingTheClockAlwaysUsesIt() {
        assertEquals(PacingMode.CLOCK, PacingDecision.choose(60.0, NTSC, PacingMode.CLOCK).mode());
    }
}
