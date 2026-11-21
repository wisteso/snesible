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
 *       keeps much more treble than the Gaussian, without sinc's sharpness.</li>
 *   <li>{@link #SINC}: 8 points of the ideal band-limiting curve (sin(πx)/πx), tapered at its ends
 *       by a Kaiser window. It keeps the most treble.</li>
 * </ul>
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

    SINC(4) {
        /** Kaiser's β: larger tapers harder, giving less leakage but a softer top end. */
        private static final double BETA = 6.0;

        @Override
        double kernel(double x) {
            if (Math.abs(x) >= 4) {
                return 0;
            }
            double sinc = x == 0 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
            double t = x / 4;
            double window = besselI0(BETA * Math.sqrt(1 - t * t)) / besselI0(BETA);
            return sinc * window;
        }
    };

    /** How far the kernel reaches on each side, in samples, before any stretching. */
    final int radius;

    Interpolator(int radius) {
        this.radius = radius;
    }

    /** The kernel's height at distance {@code x} (in samples) from the output position. */
    abstract double kernel(double x);

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
