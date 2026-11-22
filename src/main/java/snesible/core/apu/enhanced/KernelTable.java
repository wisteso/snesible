package snesible.core.apu.enhanced;

import java.util.function.DoubleUnaryOperator;

/**
 * Precomputed weights for one {@link Interpolator}, including its anti-aliased versions: a
 * polyphase filter bank.
 *
 * <h2>Anti-aliasing by stretching the kernel</h2>
 * When a voice plays a sample faster than it was recorded, each output sample steps over more
 * than one stored sample. Frequencies in the sample above the output's limit then can't be
 * reproduced, and a plain interpolator folds them back down as harsh, unrelated tones (aliasing).
 * The cure is to filter them out first. Stretching the kernel to {@code step} times its width
 * does exactly that: a kernel twice as wide only passes frequencies half as high. So for a pitch
 * step above 1 we use the kernel stretched by the step, which reads {@code step} times as many
 * stored samples. At a step of 1 or below nothing changes.
 *
 * <h2>Bands</h2>
 * Computing kernels for every exact step would be slow, so there are 17 bands from step 1 to 4,
 * eight per octave (each about 9% apart), and a voice uses the first band at or above its step:
 * its cut-off is at most 9% lower than ideal, never higher. The DSP's pitch tops out just under
 * a step of 4; faster (possible with pitch modulation) uses the last band.
 *
 * <h2>Phases</h2>
 * Within a band, weights are stored for 257 positions between two samples (0/256 to 256/256),
 * and the renderer blends the two nearest, which is as good as computing them exactly.
 *
 * <h2>Which samples a kernel reads</h2>
 * A symmetric kernel reads as far before the output position as after it. The minimum-phase one
 * ({@link MinimumPhase}) reads almost only samples before it: up to about one sample after (to
 * cancel its delay) and the rest before. Each band stores the offset of its first tap from the
 * whole sample at the output position.
 */
final class KernelTable {

    static final int PHASES = 256;
    static final int BANDS_PER_OCTAVE = 8;
    /** Bands 0-16: steps 1 to 4 (two octaves). */
    static final int BANDS = 2 * BANDS_PER_OCTAVE + 1;

    /** For each band: tap j reads the sample at (whole sample + firstTap + j). */
    private final int[] firstTap = new int[BANDS];
    private final int[] taps = new int[BANDS];
    /** For each band, {@code PHASES + 1} rows of {@code taps} weights, one after another. */
    private final float[][] weights = new float[BANDS][];

    /**
     * @param weight      the kernel's height at a distance (stored sample minus output position,
     *                    in samples, before stretching)
     * @param from        the kernel is zero at distances up to this ...
     * @param to          ... and from this on
     */
    private KernelTable(DoubleUnaryOperator weight, double from, double to) {
        for (int band = 0; band < BANDS; band++) {
            double step = stepOfBand(band);
            // Tap offsets o whose distance (o - fraction) lies inside (from, to) × step for some
            // fraction in [0, 1). The small margins keep rounding from adding or losing a tap.
            int first = (int) Math.floor(from * step + 1e-9) + 1;
            int last = (int) Math.ceil(to * step + 1 - 1e-9) - 1;
            int count = last - first + 1;
            firstTap[band] = first;
            taps[band] = count;
            float[] table = new float[(PHASES + 1) * count];
            for (int phase = 0; phase <= PHASES; phase++) {
                double fraction = phase / (double) PHASES;
                double[] row = new double[count];
                double total = 0;
                for (int j = 0; j < count; j++) {
                    double distance = (first + j) - fraction;
                    row[j] = weight.applyAsDouble(distance / step);
                    total += row[j];
                }
                // Make each row add up to exactly 1, so a constant signal comes out unchanged.
                for (int j = 0; j < count; j++) {
                    table[phase * count + j] = (float) (row[j] / total);
                }
            }
            weights[band] = table;
        }
    }

    /** The table for a symmetric kernel (cubic, sinc). */
    static KernelTable linearPhase(Interpolator interpolator) {
        return new KernelTable(interpolator::kernel, -interpolator.radius, interpolator.radius);
    }

    /**
     * The table for a minimum-phase kernel. Its response starts at time 0; reading it at
     * (delay - distance) lines its low frequencies up with the output position.
     */
    static KernelTable minimumPhase(MinimumPhase kernel) {
        double delay = kernel.delay();
        return new KernelTable(distance -> kernel.at(delay - distance), delay - kernel.span(), delay);
    }

    /** The pitch step a band is built for: 2^(band / 8). */
    static double stepOfBand(int band) {
        return Math.pow(2, band / (double) BANDS_PER_OCTAVE);
    }

    /** The first band whose step is at least {@code step} (band 0 for steps up to 1). */
    static int bandFor(double step) {
        if (step <= 1) {
            return 0;
        }
        double exact = BANDS_PER_OCTAVE * Math.log(step) / Math.log(2);
        return Math.min(BANDS - 1, (int) Math.ceil(exact - 1e-9));
    }

    /** How many samples after the output position's whole sample the band reads, at most. */
    int lookAhead(int band) {
        return firstTap[band] + taps[band] - 1;
    }

    /** How many samples before the output position's whole sample the band reads, at most. */
    int lookBehind(int band) {
        return -firstTap[band];
    }

    /**
     * Interpolates {@code history} (a ring indexed by absolute sample number, {@code mask + 1}
     * long) at position {@code index + fraction / 4096}.
     *
     * @param index    the whole sample at or before the output position
     * @param fraction 0-4095: how far past {@code index}, in 1/4096ths of a sample
     * @param newest   the newest sample in the history; later ones aren't decoded yet, and the
     *                 newest is read in their place (only at extremely low pitches, see
     *                 {@link EnhancedRenderer})
     */
    float interpolate(float[] history, int mask, long index, int fraction, int band, long newest) {
        int count = taps[band];
        float[] table = weights[band];
        // 4096 fractions over 256 phases: the top 8 bits pick a row, the low 4 blend it with the next.
        int row = (fraction >> 4) * count;
        float blend = (fraction & 15) / 16f;
        long first = index + firstTap[band];
        float sum = 0;
        for (int j = 0; j < count; j++) {
            float weight = table[row + j] + (table[row + count + j] - table[row + j]) * blend;
            long sample = Math.min(first + j, newest);
            sum += weight * history[(int) (sample & mask)];
        }
        return sum;
    }
}
