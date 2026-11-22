package snesible.core.apu.enhanced;

/**
 * The minimum-phase version of a symmetric (linear-phase) kernel such as the sinc.
 *
 * <h2>Why</h2>
 * A sinc kernel is symmetric: it reaches as far before the output position as after it. So a
 * sudden change in a sample (a drum hit) already shows up in the output a little <i>before</i> it
 * happens, as a faint ripple: "pre-ringing". Natural sounds never ring before they start, and the
 * ear notices it more than ringing afterwards, which ordinary echoes and instruments produce
 * anyway. Pre-ringing lasts as long as half the kernel, in the sample's own time: for a 16-tap
 * kernel and a drum stored at 8 kHz, about a millisecond.
 *
 * <p>A <b>minimum-phase</b> kernel has exactly the same frequency response (it keeps and removes
 * the same frequencies) but puts all its ringing after the output position. The price is that
 * different frequencies are delayed by slightly different amounts, mostly near the top of the
 * band, where it's hard to hear.
 *
 * <h2>How: the cepstrum</h2>
 * A kernel's frequency response is its magnitude (how much of each frequency passes) and its
 * phase (how much each is delayed). For a given magnitude there's one phase that makes the kernel
 * as early as possible, and it can be computed from the magnitude alone:
 * <ol>
 *   <li>Take the kernel's spectrum (FFT) and the logarithm of its magnitude.</li>
 *   <li>Transform that back (the result is called the <i>cepstrum</i>).</li>
 *   <li>Fold its second half onto the first: double the first half, zero the second. This is the
 *       step that makes the result "causal": nothing before time zero.</li>
 *   <li>Transform forward again and take the exponential: that's the minimum-phase spectrum.</li>
 *   <li>Transform back to get the kernel.</li>
 * </ol>
 * It's done once, at startup, on the kernel sampled finely (256 points per sample, the same
 * resolution as the tables).
 *
 * <h2>Keeping the timing</h2>
 * The minimum-phase kernel still delays low frequencies a little (about a sample). Left alone,
 * that would make each voice late by an amount that depends on its pitch. So {@link #delay()}
 * measures that delay, and the kernel table reads that much further ahead to cancel it: a voice's
 * bass and mid-range stay exactly in time with the hardware.
 */
final class MinimumPhase {

    private static final int POINTS_PER_SAMPLE = KernelTable.PHASES;

    /** The kernel at x = n / 256 samples, n = 0 .. length-1; zero before 0 and after the end. */
    private final double[] response;
    private final double delay;
    /** How far the kernel reaches, in samples (the linear-phase kernel's full width). */
    private final double span;

    MinimumPhase(Interpolator linearPhase) {
        int radius = linearPhase.radius;
        span = 2.0 * radius;
        int length = 2 * radius * POINTS_PER_SAMPLE + 1;
        // Zero-pad generously: the cepstrum is in principle infinitely long, and padding keeps
        // its folded-over tail from wrapping around onto the part we use.
        int size = Integer.highestOneBit(length * 8 - 1) << 1;

        double[] real = new double[size];
        double[] imaginary = new double[size];
        for (int n = 0; n < length; n++) {
            real[n] = linearPhase.kernel(n / (double) POINTS_PER_SAMPLE - radius);
        }

        // 1. The spectrum's log magnitude. Near-zero values are floored at -140 dB below the peak,
        //    since log(0) doesn't exist; that's far below anything audible.
        Fft.transform(real, imaginary, false);
        double peak = 0;
        for (int k = 0; k < size; k++) {
            peak = Math.max(peak, Math.hypot(real[k], imaginary[k]));
        }
        double floor = peak * 1e-7;
        for (int k = 0; k < size; k++) {
            real[k] = Math.log(Math.max(Math.hypot(real[k], imaginary[k]), floor));
            imaginary[k] = 0;
        }
        // 2. The cepstrum.
        Fft.transform(real, imaginary, true);
        // 3. Fold: keep time zero and the middle, double the first half, zero the second.
        for (int n = 1; n < size / 2; n++) {
            real[n] *= 2;
            imaginary[n] *= 2;
        }
        for (int n = size / 2 + 1; n < size; n++) {
            real[n] = 0;
            imaginary[n] = 0;
        }
        // 4. Back to a spectrum, and undo the logarithm: e^(a + ib) = e^a (cos b + i sin b).
        Fft.transform(real, imaginary, false);
        for (int k = 0; k < size; k++) {
            double magnitude = Math.exp(real[k]);
            double phase = imaginary[k];
            real[k] = magnitude * Math.cos(phase);
            imaginary[k] = magnitude * Math.sin(phase);
        }
        // 5. The kernel. Its energy is at the start; the last tenth is tapered to zero so cutting
        //    it to the original width leaves no step.
        Fft.transform(real, imaginary, true);
        response = new double[length];
        int taperStart = length - length / 10;
        for (int n = 0; n < length; n++) {
            double taper = n < taperStart ? 1
                    : 0.5 + 0.5 * Math.cos(Math.PI * (n - taperStart) / (length - 1 - taperStart));
            response[n] = real[n] * taper;
        }

        // The delay of the lowest frequencies is the kernel's centre of mass.
        double weighted = 0;
        double total = 0;
        for (int n = 0; n < length; n++) {
            weighted += n * response[n];
            total += response[n];
        }
        delay = weighted / total / POINTS_PER_SAMPLE;
    }

    /** The kernel's height {@code x} samples after its start (0 outside it). */
    double at(double x) {
        double position = x * POINTS_PER_SAMPLE;
        if (position < 0 || position > response.length - 1) {
            return 0;
        }
        int n = (int) position;
        if (n == response.length - 1) {
            return response[n];
        }
        double t = position - n;
        return response[n] * (1 - t) + response[n + 1] * t;
    }

    /** How late the kernel makes low frequencies, in samples. */
    double delay() {
        return delay;
    }

    /** The kernel's length, in samples. */
    double span() {
        return span;
    }
}
