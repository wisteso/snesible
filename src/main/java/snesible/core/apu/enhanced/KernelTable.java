package snesible.core.apu.enhanced;

/**
 * Precomputed weights for one {@link Interpolator}, including its anti-aliased versions.
 *
 * <h2>Anti-aliasing by stretching the kernel</h2>
 * When a voice plays a sample faster than it was recorded, each output sample steps over more
 * than one stored sample. Frequencies in the sample above the output's limit then can't be
 * reproduced, and a plain interpolator folds them back down as harsh, unrelated tones (aliasing).
 * The cure is to filter them out first. Stretching the kernel to {@code step} times its width
 * does exactly that: a kernel twice as wide only passes frequencies half as high. So for a
 * pitch step above 1 we use the kernel stretched by the step, which reads {@code 2 × radius ×
 * step} stored samples instead of {@code 2 × radius}. At a step of 1 or below nothing changes.
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
 */
final class KernelTable {

    static final int PHASES = 256;
    static final int BANDS_PER_OCTAVE = 8;
    /** Bands 0-16: steps 1 to 4 (two octaves). */
    static final int BANDS = 2 * BANDS_PER_OCTAVE + 1;

    /** For each band, the taps on each side of the output position. */
    private final int[] halfTaps = new int[BANDS];
    /** For each band, {@code PHASES + 1} rows of {@code 2 × halfTaps} weights, one after another. */
    private final float[][] weights = new float[BANDS][];

    KernelTable(Interpolator interpolator) {
        for (int band = 0; band < BANDS; band++) {
            double step = stepOfBand(band);
            int half = (int) Math.ceil(interpolator.radius * step - 1e-9);
            int taps = 2 * half;
            halfTaps[band] = half;
            float[] table = new float[(PHASES + 1) * taps];
            for (int phase = 0; phase <= PHASES; phase++) {
                double fraction = phase / (double) PHASES;
                double[] row = new double[taps];
                double total = 0;
                for (int j = 0; j < taps; j++) {
                    // Tap j sits at sample (floor + j - (half - 1)), so its distance from the
                    // output position (floor + fraction) is this:
                    double distance = (j - (half - 1)) - fraction;
                    row[j] = interpolator.kernel(distance / step);
                    total += row[j];
                }
                // Make each row add up to exactly 1, so a constant signal comes out unchanged.
                for (int j = 0; j < taps; j++) {
                    table[phase * taps + j] = (float) (row[j] / total);
                }
            }
            weights[band] = table;
        }
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

    /** How many samples the band reads on each side of the output position. */
    int halfTaps(int band) {
        return halfTaps[band];
    }

    /**
     * Interpolates {@code history} (a ring indexed by absolute sample number, {@code mask + 1}
     * long) at position {@code index + fraction / 4096}.
     *
     * @param index    the whole sample at or before the output position
     * @param fraction 0-4095: how far past {@code index}, in 1/4096ths of a sample
     */
    float interpolate(float[] history, int mask, long index, int fraction, int band) {
        int half = halfTaps[band];
        int taps = 2 * half;
        float[] table = weights[band];
        // 4096 fractions over 256 phases: the top 8 bits pick a row, the low 4 blend it with the next.
        int row = (fraction >> 4) * taps;
        float blend = (fraction & 15) / 16f;
        long first = index - (half - 1);
        float sum = 0;
        for (int j = 0; j < taps; j++) {
            float weight = table[row + j] + (table[row + taps + j] - table[row + j]) * blend;
            sum += weight * history[(int) ((first + j) & mask)];
        }
        return sum;
    }
}
