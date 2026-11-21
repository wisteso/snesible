package snesible.core.apu.enhanced;

/**
 * Test support: measures how strong one frequency is in a signal, for checking anti-aliasing.
 *
 * <p>It's a single bin of a discrete Fourier transform: the signal is multiplied by a sine and a
 * cosine at the frequency and summed. A Hann window (a smooth fade in and out over the whole
 * signal) first stops the signal's abrupt start and end from leaking energy into every
 * frequency. The result is scaled so a sine of amplitude 1 at exactly that frequency reads 1.
 */
final class Spectrum {

    private Spectrum() {
    }

    /** The amplitude of {@code frequency} (cycles per sample, 0-0.5) in {@code signal}. */
    static double amplitude(double[] signal, double frequency) {
        double real = 0;
        double imaginary = 0;
        double windowSum = 0;
        int n = signal.length;
        for (int i = 0; i < n; i++) {
            double window = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
            double angle = 2 * Math.PI * frequency * i;
            real += signal[i] * window * Math.cos(angle);
            imaginary += signal[i] * window * Math.sin(angle);
            windowSum += window;
        }
        // A sine's energy splits between this frequency and its negative mirror: hence the 2.
        return 2 * Math.hypot(real, imaginary) / windowSum;
    }

    /** An amplitude as decibels relative to 1. */
    static double decibels(double amplitude) {
        return 20 * Math.log10(Math.max(amplitude, 1e-12));
    }
}
