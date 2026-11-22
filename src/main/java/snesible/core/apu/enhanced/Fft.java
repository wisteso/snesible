package snesible.core.apu.enhanced;

/**
 * A plain fast Fourier transform, used once at startup to build the minimum-phase sinc
 * ({@link MinimumPhase}). It turns a signal into its frequencies (and back), so a filter's
 * frequency response can be worked on directly.
 *
 * <p>This is the classic radix-2 version: put the input in bit-reversed order, then combine
 * pairs, then pairs of pairs, and so on, each time with the "twiddle factors" e^(-2πik/n).
 * The length must be a power of two.
 */
final class Fft {

    private Fft() {
    }

    /**
     * Transforms {@code real} + i·{@code imaginary} in place. The inverse transform also divides
     * by the length, so a forward then an inverse transform gives back the input.
     */
    static void transform(double[] real, double[] imaginary, boolean inverse) {
        int n = real.length;
        if (Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("FFT length must be a power of two: " + n);
        }
        // Bit-reversed order: element i swaps with the element whose index has i's bits reversed.
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = real[i];
                real[i] = real[j];
                real[j] = t;
                t = imaginary[i];
                imaginary[i] = imaginary[j];
                imaginary[j] = t;
            }
        }
        // Combine transforms of length/2 into transforms of length.
        for (int length = 2; length <= n; length <<= 1) {
            double angle = (inverse ? 2 : -2) * Math.PI / length;
            double stepReal = Math.cos(angle);
            double stepImaginary = Math.sin(angle);
            for (int start = 0; start < n; start += length) {
                double twiddleReal = 1;
                double twiddleImaginary = 0;
                for (int k = 0; k < length / 2; k++) {
                    int a = start + k;
                    int b = a + length / 2;
                    double bReal = real[b] * twiddleReal - imaginary[b] * twiddleImaginary;
                    double bImaginary = real[b] * twiddleImaginary + imaginary[b] * twiddleReal;
                    real[b] = real[a] - bReal;
                    imaginary[b] = imaginary[a] - bImaginary;
                    real[a] += bReal;
                    imaginary[a] += bImaginary;
                    double nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary;
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal;
                    twiddleReal = nextReal;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                real[i] /= n;
                imaginary[i] /= n;
            }
        }
    }
}
