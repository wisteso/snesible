package snesible.core.apu.enhanced;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The interpolation kernels and their anti-aliased (stretched) versions. */
class KernelTableTest {

    private static final int SIZE = 1 << 16;
    private static final int MASK = SIZE - 1;
    /** Every sample is "decoded" in these tests. */
    private static final long ALL = Long.MAX_VALUE;

    private final KernelTable cubic = Interpolator.CUBIC.table();
    private final KernelTable sinc = Interpolator.SINC.table();
    private final KernelTable minimumPhase = Interpolator.SINC_MINIMUM_PHASE.table();
    private final KernelTable sinc8 = Interpolator.SINC_8.table();
    private final KernelTable[] all = {cubic, sinc, sinc8, minimumPhase};

    @Test
    void aConstantComesOutExactlyInEveryBand() {
        float[] history = new float[SIZE];
        java.util.Arrays.fill(history, 1000);
        for (KernelTable table : all) {
            for (int band = 0; band < KernelTable.BANDS; band++) {
                for (int fraction = 0; fraction < 4096; fraction += 97) {
                    assertEquals(1000, table.interpolate(history, MASK, 1000, fraction, band, ALL), 0.01);
                }
            }
        }
    }

    /** Includes the minimum-phase kernel: its delay compensation keeps slow signals in time. */
    @Test
    void aSlowSineIsFollowedClosely() {
        float[] history = sine(0.02);
        for (KernelTable table : all) {
            for (int fraction = 0; fraction < 4096; fraction += 97) {
                double position = 1000 + fraction / 4096.0;
                double expected = Math.sin(2 * Math.PI * 0.02 * position);
                assertEquals(expected, table.interpolate(history, MASK, 1000, fraction, 0, ALL), 0.005);
            }
        }
    }

    @Test
    void atAStepOfOneTheCubicPassesThroughTheSamples() {
        float[] history = sine(0.3);
        assertEquals(history[1000], cubic.interpolate(history, MASK, 1000, 0, 0, ALL), 1e-6);
    }

    @Test
    void bandsAreChosenAtOrAboveTheStep() {
        assertEquals(0, KernelTable.bandFor(0.5));
        assertEquals(0, KernelTable.bandFor(1.0));
        assertEquals(1, KernelTable.bandFor(1.01));
        assertEquals(8, KernelTable.bandFor(2.0));
        assertEquals(KernelTable.BANDS - 1, KernelTable.bandFor(4.0));
        assertEquals(KernelTable.BANDS - 1, KernelTable.bandFor(7.0));
        for (double step = 1.01; step < 4; step += 0.07) {
            assertTrue(KernelTable.stepOfBand(KernelTable.bandFor(step)) >= step - 1e-9);
        }
    }

    /** The minimum-phase kernel reads at most a couple of samples ahead; the plain sinc 8. */
    @Test
    void theMinimumPhaseKernelHardlyLooksAhead() {
        assertEquals(8, sinc.lookAhead(0));
        assertEquals(7, sinc.lookBehind(0));
        assertTrue(minimumPhase.lookAhead(0) <= 2, "look-ahead " + minimumPhase.lookAhead(0));
        assertTrue(minimumPhase.lookBehind(0) >= 13, "look-behind " + minimumPhase.lookBehind(0));
    }

    /**
     * A sudden step in a sample at position 1000, like a drum hit. The symmetric sinc ripples
     * for several samples before it (pre-ringing). The minimum-phase sinc is exactly silent until
     * about 2 samples before (its delay compensation starts the rise that much early, so the
     * step's middle stays in place), then rises without dipping. Both settle afterwards.
     */
    @Test
    void onlyTheSymmetricSincRingsBeforeAnAttack() {
        float[] history = new float[SIZE];
        for (int i = 1000; i < SIZE; i++) {
            history[i] = 1;
        }
        double symmetricBefore = largest(sinc, history, 993, 998);
        double minimumBefore = largest(minimumPhase, history, 993, 998);
        assertTrue(symmetricBefore > 0.02, "the symmetric sinc rings first: " + symmetricBefore);
        assertTrue(minimumBefore < 1e-4, "minimum phase doesn't: " + minimumBefore);
        double previous = 0;
        for (double position = 998; position <= 1000; position += 1 / 64.0) {
            double value = at(minimumPhase, history, position);
            assertTrue(value >= previous - 1e-4, "the rise doesn't dip at " + position);
            previous = value;
        }
        assertEquals(1, at(minimumPhase, history, 1020), 0.001);
        assertEquals(1, at(sinc, history, 1020), 0.001);
    }

    /** The largest output (by size) between two positions. */
    private static double largest(KernelTable table, float[] history, double from, double to) {
        double largest = 0;
        for (double position = from; position <= to; position += 1 / 64.0) {
            largest = Math.max(largest, Math.abs(at(table, history, position)));
        }
        return largest;
    }

    private static double at(KernelTable table, float[] history, double position) {
        long index = (long) Math.floor(position);
        int fraction = (int) Math.round((position - index) * 4096) & 0xFFF;
        return table.interpolate(history, MASK, index, fraction, 0, ALL);
    }

    /**
     * Treble near the top of a sample's range (0.4 of its rate) keeps its level through both
     * sincs, played slower than recorded so every position between samples is used. (The 8-point
     * sinc used before lost 1.4 dB here.)
     */
    @Test
    void theSincsKeepTheTreble() {
        for (KernelTable table : new KernelTable[] {sinc, minimumPhase}) {
            double step = 0.37;
            double level = Spectrum.decibels(Spectrum.amplitude(play(table, 0.4, step, true), 0.4 * step));
            assertTrue(level > -0.5, "level at 0.4: " + level);
        }
    }

    /**
     * A sample holding a tone near the top of its range (0.45 cycles per sample), played 2 and
     * 4 times faster than recorded. The tone is then too high for the output, so whatever comes
     * out at its alias frequency is the unwanted fold-back. The stretched sincs must push it
     * below -60 dB; without anti-aliasing it comes through nearly at full strength.
     */
    @Test
    void antiAliasingRemovesTheFoldBack() {
        for (double step : new double[] {2, 4}) {
            double alias = folded(0.45 * step);
            double off = Spectrum.decibels(Spectrum.amplitude(play(sinc, 0.45, step, false), alias));
            double on = Spectrum.decibels(Spectrum.amplitude(play(sinc, 0.45, step, true), alias));
            double minimum = Spectrum.decibels(Spectrum.amplitude(play(minimumPhase, 0.45, step, true), alias));
            double eightPoint = Spectrum.decibels(Spectrum.amplitude(play(sinc8, 0.45, step, true), alias));
            double cubicOn = Spectrum.decibels(Spectrum.amplitude(play(cubic, 0.45, step, true), alias));
            System.out.printf("step %.0f: alias without anti-aliasing %.1f dB, sinc %.1f dB,"
                    + " 8-point %.1f dB, minimum phase %.1f dB, cubic %.1f dB%n", step, off, on, eightPoint,
                    minimum, cubicOn);
            assertTrue(off > -10, "without anti-aliasing the alias is strong: " + off);
            assertTrue(on < -60, "sinc with anti-aliasing: " + on);
            assertTrue(minimum < -60, "minimum-phase sinc with anti-aliasing: " + minimum);
            assertTrue(eightPoint < -60, "8-point sinc with anti-aliasing: " + eightPoint);
            assertTrue(cubicOn < -30, "cubic with anti-aliasing: " + cubicOn);
        }
    }

    /** A tone well inside the output's range keeps its level when anti-aliased. */
    @Test
    void antiAliasingKeepsLowerTones() {
        for (KernelTable table : new KernelTable[] {sinc, minimumPhase}) {
            for (double step : new double[] {2, 4}) {
                double toneOut = 0.05; // cycles per output sample, well below the cut-off
                double[] out = play(table, toneOut / step, step, true);
                assertEquals(0, Spectrum.decibels(Spectrum.amplitude(out, toneOut)), 0.5);
            }
        }
    }

    /** Plays a recorded sine of {@code frequency} at {@code step}, and returns 4096 output samples. */
    private static double[] play(KernelTable table, double frequency, double step, boolean antiAliasing) {
        float[] history = sine(frequency);
        int band = antiAliasing ? KernelTable.bandFor(step) : 0;
        double[] out = new double[4096];
        for (int n = 0; n < out.length; n++) {
            double position = 100.37 + n * step;
            long index = (long) Math.floor(position);
            int fraction = (int) Math.round((position - index) * 4096) & 0xFFF;
            out[n] = table.interpolate(history, MASK, index, fraction, band, ALL);
        }
        return out;
    }

    private static float[] sine(double frequency) {
        float[] history = new float[SIZE];
        for (int i = 0; i < SIZE; i++) {
            history[i] = (float) Math.sin(2 * Math.PI * frequency * i);
        }
        return history;
    }

    /** Where a frequency above the output's limit lands after folding back (0-0.5 cycles). */
    private static double folded(double frequency) {
        double f = frequency - Math.floor(frequency);
        return f > 0.5 ? 1 - f : f;
    }
}
