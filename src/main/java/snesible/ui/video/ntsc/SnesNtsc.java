/*
 * Java port of snes_ntsc 0.2.2 by Shay Green (Blargg), http://www.slack.net/~ant/
 * Copyright (C) 2006-2007 Shay Green. Java port for Snesible, 2026.
 *
 * This module is free software; you can redistribute it and/or modify it under the terms of
 * the GNU Lesser General Public License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version. This module is
 * distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details. You should have received a copy of the GNU Lesser
 * General Public License along with this module; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
package snesible.ui.video.ntsc;

/**
 * Blargg's SNES NTSC filter: turns 256-pixel SNES lines into 602-pixel lines that look like a
 * TV showing a composite, S-Video or RGB signal. (See the package documentation for the
 * signal theory.)
 *
 * <h2>Two phases</h2>
 * <ol>
 *   <li><b>Setup</b> (the constructor, a few tens of milliseconds): for each of the 8192 colors
 *       the filter distinguishes, simulate encoding that one pixel into a composite signal
 *       and decoding it again, at each of the 3 burst phases and each of the 3 positions a
 *       pixel can have within a 3-pixel chunk. The result for one case is 14 output pixels:
 *       the "smear" that this input pixel adds to its neighborhood. All of it goes into one
 *       table of 8192 x 128 entries.</li>
 *   <li><b>Filtering</b> ({@link #blit}): each output pixel is the sum of the smears of the 6
 *       input pixels near it, looked up in that table. Because the encode/decode process is
 *       linear, adding the precomputed responses gives the same result as simulating the
 *       whole line.</li>
 * </ol>
 *
 * <h2>Packed RGB arithmetic</h2>
 * The table doesn't store three numbers per entry but one {@code int} holding all three
 * channels, spaced apart with room to spare:
 * <pre>
 *   bit 31          21 20          11 10           1 0
 *       [ red ......  ][ green ....  ][ blue .....  ][ ]
 * </pre>
 * Adding two packed values adds all three channels at once (the gaps absorb carries). Each
 * channel is stored with a bias ({@link #RGB_BIAS}) so negative contributions don't borrow from
 * the neighboring channel. After summing, {@link #clamp} limits every channel to 0-255 in a
 * few bit operations, and {@link #toRgb24} unpacks the result. It's the C original's speed
 * trick; this port keeps it because the output then matches Snes9x's exactly, and it's an
 * interesting technique in its own right ("SIMD within a register").
 *
 * <p>Floating-point steps use {@code float} wherever the C code does, for the same reason.
 * Not ported: the hi-res (512-pixel) blitter, since Snesible doesn't emulate hi-res modes yet.
 */
public final class SnesNtsc {

    // ---- Geometry ----------------------------------------------------------------------------

    /** Input pixels per chunk: 3 pixels = 2 color subcarrier cycles. */
    public static final int IN_CHUNK = 3;
    /** Output pixels per chunk. */
    public static final int OUT_CHUNK = 7;
    /** Scanlines start at one of 3 subcarrier phases (0, 120, 240 degrees). */
    public static final int BURST_COUNT = 3;

    /** Table entries per input color: 3 burst phases x 42, rounded up. */
    private static final int ENTRY_SIZE = 128;
    /** Colors the table distinguishes: 4 bits red, 5 green, 4 blue (13 bits). */
    private static final int PALETTE_SIZE = 0x2000;
    private static final int BURST_SIZE = ENTRY_SIZE / BURST_COUNT;          // 42
    /** Positions a pixel can have within a 3-pixel chunk. */
    private static final int ALIGNMENT_COUNT = 3;
    /** Output pixels one input pixel contributes to (per burst phase and alignment). */
    private static final int RGB_KERNEL_SIZE = BURST_SIZE / ALIGNMENT_COUNT; // 14

    /**
     * The signal is simulated at 8 samples per 3 pixels (4 per subcarrier cycle, which makes
     * the I/Q demodulation simple), then resampled to 7 output pixels per 3.
     */
    private static final int RESCALE_IN = 8;
    private static final int RESCALE_OUT = 7;

    /** The filter kernels reach 16 samples to each side. */
    private static final int KERNEL_HALF = 16;
    private static final int KERNEL_SIZE = KERNEL_HALF * 2 + 1;

    // ---- Levels ------------------------------------------------------------------------------

    /**
     * Each channel uses half the normal range (7 bits for 0-255), leaving headroom so the
     * hi-res blitter could add twice as many contributions.
     */
    private static final int RGB_BITS = 7;
    private static final int RGB_UNIT = 1 << RGB_BITS;
    private static final float RGB_OFFSET = RGB_UNIT * 2 + 0.5f;
    /** Steps in the input gamma table (one per 5-bit channel value). */
    private static final int GAMMA_SIZE = 32;

    private static final float ARTIFACTS_MID = 1.0f;
    private static final float ARTIFACTS_MAX = ARTIFACTS_MID * 1.5f;
    private static final float FRINGING_MID = 1.0f;
    private static final float FRINGING_MAX = FRINGING_MID * 2;
    private static final int STD_DECODER_HUE = 0;
    private static final int EXT_DECODER_HUE = STD_DECODER_HUE + 15;
    private static final float LUMA_CUTOFF = 0.20f;
    private static final float PI = 3.14159265358979323846f;

    /** The standard YIQ-to-RGB decoder: I and Q weights for red, green and blue. */
    private static final float[] DEFAULT_DECODER = {0.956f, 0.621f, -0.272f, -0.647f, -1.105f, 1.702f};

    // ---- Packed RGB --------------------------------------------------------------------------

    /** A 1 in the lowest bit of each packed channel. */
    private static final int RGB_BUILDER = (1 << 21) | (1 << 11) | (1 << 1);
    /** Added to each channel so that negative values don't borrow from the next channel. */
    private static final int RGB_BIAS = RGB_UNIT * 2 * RGB_BUILDER;
    private static final int CLAMP_MASK = RGB_BUILDER * 3 / 2;
    private static final int CLAMP_ADD = RGB_BUILDER * 0x101;

    // ---- The three pixel alignments ----------------------------------------------------------

    /**
     * How each of the 3 pixels in a chunk overlaps the 8 composite samples (2 subcarrier cycles
     * of 4 samples): weights for the 4 samples it touches. Pixel 0 covers samples 0, 1 and two
     * thirds of 2; pixel 1 the rest of 2, 3, 4 and a third of 5; pixel 2 the rest.
     */
    private static final float[][] PIXEL_KERNEL = {
        {1, 1, .6667f, 0},
        {.3333f, 1, 1, .3333f},
        {0, .6667f, 1, 1},
    };
    /** Where each alignment's kernel starts (computed by {@link #pixelOffset}). */
    private static final int[] PIXEL_OFFSET = {pixelOffset(-4, -9), pixelOffset(-2, -7), pixelOffset(0, -5)};
    /** -1 when the pixel's samples start at an odd multiple of 2, i.e. on an inverted carrier. */
    private static final float[] PIXEL_NEGATE = {negate(-4), negate(-2), negate(0)};

    /**
     * The precomputed smears: for color index {@code c} and burst phase {@code p}, entries
     * {@code c * 128 + p * 42} to {@code + 41} (3 alignments x 14 output pixels).
     */
    private final int[] table = new int[PALETTE_SIZE * ENTRY_SIZE];

    // The settings, turned into internal values by the constructor (init_t in the C code).
    private final float[] toRgb = new float[BURST_COUNT * 6];
    private final float[] toFloat = new float[GAMMA_SIZE];
    private final float artifacts;
    private final float fringing;
    private final float[] kernel = new float[RESCALE_OUT * KERNEL_SIZE * 2];

    /** Builds the table for the given settings. */
    public SnesNtsc(NtscSetup setup) {
        float brightness = (float) setup.brightness() * (0.5f * RGB_UNIT) + RGB_OFFSET;
        float contrast = (float) setup.contrast() * (0.5f * RGB_UNIT) + RGB_UNIT;

        // Map -1..+1 to 0..1.5 (artifacts) and 0..2 (fringing), with 0 at the middle.
        float a = (float) setup.artifacts();
        if (a > 0) {
            a *= ARTIFACTS_MAX - ARTIFACTS_MID;
        }
        artifacts = a * ARTIFACTS_MID + ARTIFACTS_MID;
        float f = (float) setup.fringing();
        if (f > 0) {
            f *= FRINGING_MAX - FRINGING_MID;
        }
        fringing = f * FRINGING_MID + FRINGING_MID;

        initFilters(setup);
        initGamma(setup, contrast, brightness);
        initDecoder(setup);

        // Field merging is forced on when neither kind of artifact is present: without
        // artifacts the burst phases differ only by rounding, and merging hides that.
        boolean mergeFields = setup.mergeFields() || (setup.artifacts() <= -1 && setup.fringing() <= -1);

        for (int entry = 0; entry < PALETTE_SIZE; entry++) {
            // Each table entry stands for a color with 4 bits of red, 5 of green and 4 of blue
            // (the low bits of red and blue are least noticeable). Expand back to 5 bits each.
            int ir = entry >> 8 & 0x1E;
            int ig = entry >> 4 & 0x1F;
            int ib = entry << 1 & 0x1E;

            float rr = toFloat[ir];
            float gg = toFloat[ig];
            float bb = toFloat[ib];

            // RGB to YIQ: luma, and the two color-difference signals.
            float y = rr * 0.299f + gg * 0.587f + bb * 0.114f;
            float i = rr * 0.596f - gg * 0.275f - bb * 0.321f;
            float q = rr * 0.212f - gg * 0.523f + bb * 0.311f;

            // The color this entry *should* average to, for the error correction below.
            int r = (int) (y + toRgb[0] * i + toRgb[1] * q);
            int g = (int) (y + toRgb[2] * i + toRgb[3] * q);
            int b = (int) (y + toRgb[4] * i + toRgb[5] * q);
            int rgb = packRgb(r, g, b);

            int out = entry * ENTRY_SIZE;
            genKernel(y, i, q, out);
            if (mergeFields) {
                mergeKernelFields(out);
            }
            correctErrors(rgb, out);
        }
    }

    // =============================================================================================
    // Setup: filter kernels
    // =============================================================================================

    /**
     * Builds the luma and chroma filter kernels, then 7 resampled copies of them (one per output
     * pixel position in a chunk) for the 8-to-7 rescale.
     */
    private void initFilters(NtscSetup setup) {
        // kernels[0..32] is the chroma (I/Q) filter, kernels[33..65] the luma (Y) filter.
        float[] kernels = new float[KERNEL_SIZE * 2];

        // Luma: a sinc-like low-pass ("dsf" = discrete summation formula, a sinc with
        // rolloff). Sharpness controls the rolloff, resolution the cutoff frequency.
        {
            float rolloff = 1 + (float) setup.sharpness() * 0.032f;
            float maxh = 32;
            float powAN = (float) Math.pow(rolloff, maxh);
            // Quadratic mapping to reduce the negative (blurring) range.
            float toAngle = (float) setup.resolution() + 1;
            toAngle = PI / maxh * LUMA_CUTOFF * (toAngle * toAngle + 1);

            kernels[KERNEL_SIZE * 3 / 2] = maxh; // default center value
            for (int i = 0; i < KERNEL_HALF * 2 + 1; i++) {
                int x = i - KERNEL_HALF;
                float angle = x * toAngle;
                // The formula is unstable at the center point when rolloff is very close to 1.
                if (x != 0 || powAN > 1.056f || powAN < 0.981f) {
                    float rolloffCosA = rolloff * (float) Math.cos(angle);
                    float num = 1 - rolloffCosA
                            - powAN * (float) Math.cos(maxh * angle)
                            + powAN * rolloff * (float) Math.cos((maxh - 1) * angle);
                    float den = 1 - rolloffCosA - rolloffCosA + rolloff * rolloff;
                    float dsf = num / den;
                    kernels[KERNEL_SIZE * 3 / 2 - KERNEL_HALF + i] = dsf - 0.5f;
                }
            }

            // Apply a Blackman window (tapers the ends to avoid ringing), then normalize so the
            // kernel sums to 1 and flat areas keep their brightness.
            float sum = 0;
            for (int i = 0; i < KERNEL_HALF * 2 + 1; i++) {
                float x = PI * 2 / (KERNEL_HALF * 2) * i;
                float blackman = 0.42f - 0.5f * (float) Math.cos(x) + 0.08f * (float) Math.cos(x * 2);
                sum += (kernels[KERNEL_SIZE * 3 / 2 - KERNEL_HALF + i] *= blackman);
            }
            sum = 1.0f / sum;
            for (int i = 0; i < KERNEL_HALF * 2 + 1; i++) {
                kernels[KERNEL_SIZE * 3 / 2 - KERNEL_HALF + i] *= sum;
            }
        }

        // Chroma: a Gaussian low-pass. Bleed controls its width.
        {
            float cutoffFactor = -0.03125f;
            float cutoff = (float) setup.bleed();
            if (cutoff < 0) {
                // Keep the extreme value reachable only near the end of the scale.
                cutoff *= cutoff;
                cutoff *= cutoff;
                cutoff *= cutoff;
                cutoff *= -30.0f / 0.65f;
            }
            cutoff = cutoffFactor - 0.65f * cutoffFactor * cutoff;

            for (int i = -KERNEL_HALF; i <= KERNEL_HALF; i++) {
                kernels[KERNEL_SIZE / 2 + i] = (float) Math.exp(i * i * cutoff);
            }

            // Normalize the even and odd taps separately: I and Q are sampled on alternate
            // samples, so each must sum to 1 on its own.
            for (int i = 0; i < 2; i++) {
                float sum = 0;
                for (int x = i; x < KERNEL_SIZE; x += 2) {
                    sum += kernels[x];
                }
                sum = 1.0f / sum;
                for (int x = i; x < KERNEL_SIZE; x += 2) {
                    kernels[x] *= sum;
                }
            }
        }

        // Linear-interpolation copies for the 8-to-7 rescale: copy n is shifted by n/8 of a
        // sample, so output pixel n of a chunk can read its kernel at the right sub-position.
        float weight = 1.0f;
        int out = 0;
        for (int n = 0; n < RESCALE_OUT; n++) {
            float remain = 0;
            weight -= 1.0f / RESCALE_IN;
            for (int i = 0; i < KERNEL_SIZE * 2; i++) {
                float cur = kernels[i];
                float m = cur * weight;
                kernel[out++] = m + remain;
                remain = cur - m;
            }
        }
    }

    /**
     * Converts 5-bit channel values to light levels. A gamma of 1.1333 adapts a PC monitor's
     * 2.2 gamma to a TV's (about 2.65); contrast and brightness then scale and offset it.
     */
    private void initGamma(NtscSetup setup, float contrast, float brightness) {
        float step = 1.0f / (GAMMA_SIZE - 1);
        float gamma = 1.1333f - (float) setup.gamma() * 0.5f;
        for (int i = 0; i < GAMMA_SIZE; i++) {
            toFloat[i] = (float) Math.pow(i * step, gamma) * contrast + brightness;
        }
    }

    /**
     * Builds the YIQ-to-RGB matrix for each burst phase: hue rotates the I/Q plane, saturation
     * scales it, and each further phase is rotated another 120 degrees (the decoder follows
     * the line's color burst).
     */
    private void initDecoder(NtscSetup setup) {
        float hue = (float) setup.hue() * PI + PI / 180 * EXT_DECODER_HUE;
        float sat = (float) setup.saturation() + 1;
        float[] decoder = DEFAULT_DECODER;
        hue += PI / 180 * (STD_DECODER_HUE - EXT_DECODER_HUE); // the standard decoder's own hue

        float s = (float) Math.sin(hue) * sat;
        float c = (float) Math.cos(hue) * sat;
        int out = 0;
        for (int burst = 0; burst < BURST_COUNT; burst++) {
            for (int k = 0; k < 3; k++) {
                float i = decoder[k * 2];
                float q = decoder[k * 2 + 1];
                toRgb[out++] = i * c - q * s;
                toRgb[out++] = i * s + q * c;
            }
            // Rotate (s, c) by +120 degrees: sin 120 = 0.866025, cos 120 = -0.5.
            float t = s * -0.5f - c * 0.866025f;
            c = s * 0.866025f + c * -0.5f;
            s = t;
        }
    }

    // =============================================================================================
    // Setup: one color's smears
    // =============================================================================================

    /**
     * Simulates one pixel of color (y, i, q) at every burst phase and alignment, writing the
     * 3 x 3 x 14 packed output contributions starting at {@code table[out]}.
     *
     * <p>The pixel is encoded into <i>two</i> composite signals, one for the luma decoder and
     * one for the chroma decoder, so artifacts and fringing can be scaled separately: in the
     * signal the luma decoder sees, chroma leaks in by {@code artifacts}; in the one the
     * chroma decoder sees, luma leaks in by {@code fringing}. Each is convolved with its filter
     * (which also applies sharpening and the 8-to-7 rescale) and converted back to RGB.
     * (Based on an algorithm by NewRisingSun, per the original.)
     */
    private void genKernel(float y, float i, float q, int out) {
        int toRgbIndex = 0;
        y -= RGB_OFFSET;
        for (int burst = 0; burst < BURST_COUNT; burst++) {
            for (int p = 0; p < ALIGNMENT_COUNT; p++) {
                float[] pk = PIXEL_KERNEL[p];
                float negate = PIXEL_NEGATE[p];

                // The chroma decoder's signal: I and Q, plus luma leaking in (fringing). The 4
                // samples per subcarrier cycle read +I, +Q, -I, -Q, which is why samples 2 and 3
                // subtract.
                float yy = y * fringing * negate;
                float ic0 = (i + yy) * pk[0];
                float qc1 = (q + yy) * pk[1];
                float ic2 = (i - yy) * pk[2];
                float qc3 = (q - yy) * pk[3];

                // The luma decoder's signal: Y, plus chroma leaking in (artifacts).
                float factor = artifacts * negate;
                float ii = i * factor;
                float yc0 = (y + ii) * pk[0];
                float yc2 = (y - ii) * pk[2];
                float qq = q * factor;
                float yc1 = (y + qq) * pk[1];
                float yc3 = (y - qq) * pk[3];

                int k = PIXEL_OFFSET[p];
                for (int n = 0; n < RGB_KERNEL_SIZE; n++) {
                    float fi = kernel[k] * ic0 + kernel[k + 2] * ic2;
                    float fq = kernel[k + 1] * qc1 + kernel[k + 3] * qc3;
                    float fy = kernel[k + KERNEL_SIZE] * yc0 + kernel[k + KERNEL_SIZE + 1] * yc1
                            + kernel[k + KERNEL_SIZE + 2] * yc2 + kernel[k + KERNEL_SIZE + 3] * yc3
                            + RGB_OFFSET;
                    // Step to the next output pixel: move to the next rescale copy, or wrap
                    // back to the first copy two samples earlier.
                    if (k < KERNEL_SIZE * 2 * (RESCALE_OUT - 1)) {
                        k += KERNEL_SIZE * 2 - 1;
                    } else {
                        k -= KERNEL_SIZE * 2 * (RESCALE_OUT - 1) + 2;
                    }
                    int r = (int) (fy + toRgb[toRgbIndex] * fi + toRgb[toRgbIndex + 1] * fq);
                    int g = (int) (fy + toRgb[toRgbIndex + 2] * fi + toRgb[toRgbIndex + 3] * fq);
                    int b = (int) (fy + toRgb[toRgbIndex + 4] * fi + toRgb[toRgbIndex + 5] * fq);
                    table[out++] = packRgb(r, g, b) - RGB_BIAS;
                }
            }
            toRgbIndex += 6;
            // The next burst phase starts 120 degrees later, so rotate I/Q by -120 degrees.
            float t = i * -0.5f - q * -0.866025f;
            q = i * -0.866025f + q * -0.5f;
            i = t;
        }
    }

    /**
     * Averages each burst phase's smear with the next phase's, which is what the eye does with
     * two quickly alternating fields. Packed channels are averaged without losing the low
     * bit: {@code (a + b - ((a ^ b) & builder)) / 2} rounds each channel down exactly.
     */
    private void mergeKernelFields(int io) {
        for (int n = 0; n < BURST_SIZE; n++, io++) {
            int p0 = table[io] + RGB_BIAS;
            int p1 = table[io + BURST_SIZE] + RGB_BIAS;
            int p2 = table[io + BURST_SIZE * 2] + RGB_BIAS;
            table[io] = ((p0 + p1 - ((p0 ^ p1) & RGB_BUILDER)) >>> 1) - RGB_BIAS;
            table[io + BURST_SIZE] = ((p1 + p2 - ((p1 ^ p2) & RGB_BUILDER)) >>> 1) - RGB_BIAS;
            table[io + BURST_SIZE * 2] = ((p2 + p0 - ((p2 ^ p0) & RGB_BUILDER)) >>> 1) - RGB_BIAS;
        }
    }

    /**
     * Rounding makes the 6 contributions to an output pixel add up to slightly the wrong
     * color. For each output position, compares the sum a flat field of this color would
     * produce against the color itself, and spreads the error over the contributions so a
     * flat area comes out exactly right.
     */
    private void correctErrors(int color, int out) {
        for (int burst = 0; burst < BURST_COUNT; burst++) {
            for (int i = 0; i < RGB_KERNEL_SIZE / 2; i++) {
                int error = color
                        - table[out + i] - table[out + (i + 12) % 14 + 14] - table[out + (i + 10) % 14 + 28]
                        - table[out + i + 7] - table[out + i + 5 + 14] - table[out + i + 3 + 28];
                // Give a quarter of the error to three of the contributions and the rest to
                // the fourth, per channel (the masks keep the channels apart).
                int fourth = (error + 2 * RGB_BUILDER) >>> 2;
                fourth &= (RGB_BIAS >> 1) - RGB_BUILDER;
                fourth -= RGB_BIAS >> 2;
                table[out + i + 3 + 28] += fourth;
                table[out + i + 5 + 14] += fourth;
                table[out + i + 7] += fourth;
                table[out + i] += error - fourth * 3;
            }
            out += ALIGNMENT_COUNT * RGB_KERNEL_SIZE;
        }
    }

    // =============================================================================================
    // Filtering
    // =============================================================================================

    /**
     * The precomputed smears, for running the filtering step elsewhere (the OpenGL window does
     * it in a shader). Color index {@code c} (see {@link #entryFor}) and burst phase {@code p}
     * occupy entries {@code c * 128 + p * 42} to {@code + 41}: 3 alignments x 14 packed RGB
     * values. Read-only: the array is shared, not copied.
     */
    public int[] table() {
        return table;
    }

    /** Output width for a line of {@code inWidth} pixels (602 for 256). */
    public static int outWidth(int inWidth) {
        return ((inWidth - 1) / IN_CHUNK + 1) * OUT_CHUNK;
    }

    /**
     * The table position for a color: 13-bit index (4 bits red, 5 green, 4 blue) times the
     * entry size. Burst phase {@code p} adds {@code p * 42}.
     *
     * @param r5 red, 0-31
     * @param g5 green, 0-31
     * @param b5 blue, 0-31
     */
    public static int entryFor(int r5, int g5, int b5) {
        int index = ((r5 >> 1) << 9) | (g5 << 4) | (b5 >> 1);
        return index * ENTRY_SIZE;
    }

    /**
     * Filters {@code height} lines.
     *
     * @param entries    input pixels, already turned into table positions by {@link #entryFor}
     * @param inStride   distance between input rows
     * @param burstPhase the first line's phase, 0-2; each following line advances by one
     * @param inWidth    input pixels per line (256)
     * @param height     input lines
     * @param out        receives 0xRRGGBB pixels, {@link #outWidth}(inWidth) per line
     * @param outStride  distance between output rows
     * @param doubleRows write each line twice (Snes9x always does this with the NTSC filter,
     *                   which gives the 602 x 448 picture roughly a TV's 4:3 shape)
     * @param scanlines  when rows are doubled: darken the second copy by 12.5%, as Snes9x's
     *                   "NTSC scanlines" option does; otherwise the copy is identical
     */
    public void blit(int[] entries, int inStride, int burstPhase, int inWidth, int height,
                     int[] out, int outStride, boolean doubleRows, boolean scanlines) {
        int chunkCount = (inWidth - 1) / IN_CHUNK;
        int black = entryFor(0, 0, 0);
        int inRow = 0;
        int outRow = 0;
        for (int line = 0; line < height; line++) {
            int phase = burstPhase * BURST_SIZE;
            int in = inRow;
            int o = outRow;

            // The three most recent pixels (k0-k2) and the three before them (kx0-kx2). A
            // line starts with two black pixels before the first real one.
            int k0 = black + phase;
            int k1 = black + phase;
            int k2 = entries[in++] + phase;
            int kx0;
            int kx1 = k0;
            int kx2 = k0;

            for (int n = 0; n <= chunkCount; n++) {
                // After the last chunk, one more round with black pixels finishes the smear
                // of the line's last pixels.
                boolean last = n == chunkCount;
                kx0 = k0;
                k0 = (last ? black : entries[in]) + phase;
                out[o] = pixel(0, k0, k1, k2, kx0, kx1, kx2);
                out[o + 1] = pixel(1, k0, k1, k2, kx0, kx1, kx2);

                kx1 = k1;
                k1 = (last ? black : entries[in + 1]) + phase;
                out[o + 2] = pixel(2, k0, k1, k2, kx0, kx1, kx2);
                out[o + 3] = pixel(3, k0, k1, k2, kx0, kx1, kx2);

                kx2 = k2;
                k2 = (last ? black : entries[in + 2]) + phase;
                out[o + 4] = pixel(4, k0, k1, k2, kx0, kx1, kx2);
                out[o + 5] = pixel(5, k0, k1, k2, kx0, kx1, kx2);
                out[o + 6] = pixel(6, k0, k1, k2, kx0, kx1, kx2);

                in += IN_CHUNK;
                o += OUT_CHUNK;
            }

            if (doubleRows) {
                int width = outWidth(inWidth);
                for (int x = 0; x < width; x++) {
                    int rgb = out[outRow + x];
                    // c - c/8 per channel: the masks drop the bits that would cross channels.
                    out[outRow + outStride + x] = scanlines ? rgb - ((rgb >>> 3) & 0x1F1F1F) : rgb;
                }
                outRow += outStride;
            }

            burstPhase = (burstPhase + 1) % BURST_COUNT;
            inRow += inStride;
            outRow += outStride;
        }
    }

    /**
     * Output pixel {@code x} (0-6) of the current chunk: the sum of the six nearby input
     * pixels' contributions, each read at the position this output pixel has in its smear.
     */
    private int pixel(int x, int k0, int k1, int k2, int kx0, int kx1, int kx2) {
        int[] t = table;
        int raw = t[k0 + x] + t[k1 + (x + 12) % 7 + 14] + t[k2 + (x + 10) % 7 + 28]
                + t[kx0 + (x + 7) % 14] + t[kx1 + (x + 5) % 7 + 21] + t[kx2 + (x + 3) % 7 + 35];
        return toRgb24(clamp(raw, 1));
    }

    // =============================================================================================
    // Packed RGB helpers
    // =============================================================================================

    private static int packRgb(int r, int g, int b) {
        return r << 21 | g << 11 | b << 1;
    }

    /**
     * Limits each packed channel to its valid range without branches. The bits just above
     * each channel show whether it overflowed (went above the top) or underflowed (wrapped
     * below zero); from them a mask is built that forces the channel to all-ones or zero.
     */
    private static int clamp(int io, int shift) {
        int sub = (io >>> (9 - shift)) & CLAMP_MASK;
        int clamp = CLAMP_ADD - sub;
        io |= clamp;
        clamp -= sub;
        io &= clamp;
        return io;
    }

    /** Unpacks a clamped low-res sum into 0xRRGGBB. */
    private static int toRgb24(int raw) {
        return ((raw >>> 4) & 0xFF0000) | ((raw >>> 2) & 0xFF00) | (raw & 0xFF);
    }

    // =============================================================================================
    // Alignment tables (the PIXEL_OFFSET macro)
    // =============================================================================================

    /**
     * Where an alignment's kernel reads start in {@link #kernel}, given the pixel's offset in
     * composite samples ({@code ntsc}) and in output pixels ({@code scaled}). Ported from the
     * C macro, including its integer-division rounding toward zero.
     */
    private static int pixelOffset(int ntsc, int scaled) {
        int n = ntsc - scaled / RESCALE_OUT * RESCALE_IN;
        int s = (scaled + RESCALE_OUT * 10) % RESCALE_OUT;
        return KERNEL_SIZE / 2 + n + (s != 0 ? 1 : 0) + (RESCALE_OUT - s) % RESCALE_OUT
                + (KERNEL_SIZE * 2 * s);
    }

    /** -1 when the pixel's first sample is at an odd multiple of 2 (an inverted carrier). */
    private static float negate(int ntsc) {
        return 1.0f - ((ntsc + 100) & 2);
    }
}
