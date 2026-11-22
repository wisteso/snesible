package snesible.core.apu.enhanced;

/**
 * How the enhanced renderer reads a voice's samples between the stored points.
 *
 * <p>A voice plays its sample at some pitch, so each output sample falls somewhere between two
 * stored samples, and the curve through the stored points decides what's heard. Every method here
 * is a <b>kernel</b>: a small bell-shaped curve centred on the output position, whose height at
 * each stored sample is that sample's weight.
 * <ul>
 *   <li>{@link #GAUSSIAN}: the hardware's own 4-point curve, a gentle low-pass filter. It's used
 *       to check that the renderer matches the DSP, not as a preset.</li>
 *   <li>{@link #CUBIC}: a 4-point Catmull-Rom spline. It passes through every stored sample and
 *       keeps much more treble than the Gaussian, but lets more aliasing through than the sinc.</li>
 *   <li>{@link #SINC}: 16 points of the ideal band-limiting curve (sin(πx)/πx), tapered at its
 *       ends by a Kaiser window. It keeps nearly all of a sample's treble (within 0.2 dB up to
 *       80% of the way to the sample's highest frequency) and blocks the mirror images that
 *       resampling makes (by 33 dB just above that frequency, 76 dB further up). Eight points
 *       would lose 1.4 dB of that treble and block the images by only 16 dB.</li>
 *   <li>{@link #SINC_8}: the first, 8-point version of the sinc, kept for comparing by ear.</li>
 *   <li>{@link #SINC_MINIMUM_PHASE}: the 16-point sinc's frequency response, with all the ringing moved after
 *       the output position, so nothing rings before a sharp attack ({@link MinimumPhase}).</li>
 * </ul>
 * The tables for each are built the first time they're needed and then shared.
 */
enum Interpolator {

    GAUSSIAN(0) {
        @Override
        double kernel(double x) {
            throw new UnsupportedOperationException("the Gaussian uses the DSP's own table");
        }
    },

    CUBIC(2) {
        /** Catmull-Rom: 1 at 0, 0 at every other whole number, with a smooth curve between. */
        @Override
        double kernel(double x) {
            double a = Math.abs(x);
            if (a < 1) {
                return 1.5 * a * a * a - 2.5 * a * a + 1;
            }
            if (a < 2) {
                return -0.5 * a * a * a + 2.5 * a * a - 4 * a + 2;
            }
            return 0;
        }
    },

    SINC(8) {
        @Override
        double kernel(double x) {
            return windowedSinc(x, radius, KAISER_BETA);
        }
    },

    SINC_8(4) {
        /** A shorter kernel needs a gentler window to keep its stopband: β 6 for 8 taps. */
        @Override
        double kernel(double x) {
            return windowedSinc(x, radius, 6.0);
        }
    },

    SINC_MINIMUM_PHASE(8) {
        /** The linear-phase sinc it's made from; the table turns it into minimum phase. */
        @Override
        double kernel(double x) {
            return windowedSinc(x, radius, KAISER_BETA);
        }
    };

    /**
     * Kaiser's β for the 16-point sinc: larger tapers harder, so less leaks through above the
     * cut-off, but the top of the band rolls off sooner. 7 suits 16 taps: the images it lets
     * through are below -75 dB once they're a little above the cut-off.
     */
    private static final double KAISER_BETA = 7.0;

    /** How far the kernel reaches on each side, in samples, before any stretching. */
    final int radius;

    private KernelTable table;

    Interpolator(int radius) {
        this.radius = radius;
    }

    /** The kernel's height at distance {@code x} (in samples) from the output position. */
    abstract double kernel(double x);

    /** This interpolator's precomputed weights (not for the Gaussian), built on first use. */
    synchronized KernelTable table() {
        if (table == null) {
            table = this == SINC_MINIMUM_PHASE
                    ? KernelTable.minimumPhase(new MinimumPhase(SINC))
                    : KernelTable.linearPhase(this);
        }
        return table;
    }

    /** sin(πx)/πx, faded to zero at ±radius by a Kaiser window of the given β. */
    private static double windowedSinc(double x, int radius, double beta) {
        if (Math.abs(x) >= radius) {
            return 0;
        }
        double sinc = x == 0 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
        double t = x / radius;
        return sinc * besselI0(beta * Math.sqrt(1 - t * t)) / besselI0(beta);
    }

    /**
     * The modified Bessel function I0, which shapes the Kaiser window. Summing its power series
     * until the terms stop mattering is plenty for building tables.
     */
    private static double besselI0(double x) {
        double sum = 1;
        double term = 1;
        for (int k = 1; k < 50; k++) {
            term *= (x / (2 * k)) * (x / (2 * k));
            sum += term;
            if (term < sum * 1e-12) {
                break;
            }
        }
        return sum;
    }
}
