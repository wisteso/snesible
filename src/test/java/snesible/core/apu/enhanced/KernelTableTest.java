package snesible.core.apu.enhanced;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The interpolation kernels and their anti-aliased (stretched) versions. */
class KernelTableTest {

    private static final int SIZE = 1 << 16;
    private static final int MASK = SIZE - 1;

    private final KernelTable cubic = new KernelTable(Interpolator.CUBIC);
    private final KernelTable sinc = new KernelTable(Interpolator.SINC);

    @Test
    void aConstantComesOutExactlyInEveryBand() {
        float[] history = new float[SIZE];
        java.util.Arrays.fill(history, 1000);
        for (KernelTable table : new KernelTable[] {cubic, sinc}) {
            for (int band = 0; band < KernelTable.BANDS; band++) {
                for (int fraction = 0; fraction < 4096; fraction += 97) {
                    assertEquals(1000, table.interpolate(history, MASK, 1000, fraction, band), 0.01);
                }
            }
        }
    }

    @Test
    void aSlowSineIsFollowedClosely() {
        float[] history = sine(0.02);
        for (KernelTable table : new KernelTable[] {cubic, sinc}) {
            for (int fraction = 0; fraction < 4096; fraction += 97) {
                double position = 1000 + fraction / 4096.0;
                double expected = Math.sin(2 * Math.PI * 0.02 * position);
                assertEquals(expected, table.interpolate(history, MASK, 1000, fraction, 0), 0.002);
            }
        }
    }

    @Test
    void atAStepOfOneTheCubicPassesThroughTheSamples() {
        float[] history = sine(0.3);
        assertEquals(history[1000], cubic.interpolate(history, MASK, 1000, 0, 0), 1e-6);
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

    /**
     * A sample holding a tone near the top of its range (0.45 cycles per sample), played 2 and
     * 4 times faster than recorded. The tone is then too high for the output, so whatever comes
     * out at its alias frequency is the unwanted fold-back. The stretched sinc must push it below
     * -60 dB; without anti-aliasing it comes through nearly at full strength.
     */
    @Test
    void antiAliasingRemovesTheFoldBack() {
        for (double step : new double[] {2, 4}) {
            double alias = folded(0.45 * step);
            double off = Spectrum.decibels(Spectrum.amplitude(play(sinc, 0.45, step, false), alias));
            double on = Spectrum.decibels(Spectrum.amplitude(play(sinc, 0.45, step, true), alias));
            double cubicOn = Spectrum.decibels(Spectrum.amplitude(play(cubic, 0.45, step, true), alias));
            System.out.printf("step %.0f: alias without anti-aliasing %.1f dB, sinc %.1f dB, cubic %.1f dB%n",
                    step, off, on, cubicOn);
            assertTrue(off > -10, "without anti-aliasing the alias is strong: " + off);
            assertTrue(on < -60, "sinc with anti-aliasing: " + on);
            assertTrue(cubicOn < -30, "cubic with anti-aliasing: " + cubicOn);
        }
    }

    /** A tone well inside the output's range keeps its level when anti-aliased. */
    @Test
    void antiAliasingKeepsLowerTones() {
        for (double step : new double[] {2, 4}) {
            double toneOut = 0.05; // cycles per output sample, well below the cut-off
            double[] out = play(sinc, toneOut / step, step, true);
            assertEquals(0, Spectrum.decibels(Spectrum.amplitude(out, toneOut)), 0.5);
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
            out[n] = table.interpolate(history, MASK, index, fraction, band);
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
