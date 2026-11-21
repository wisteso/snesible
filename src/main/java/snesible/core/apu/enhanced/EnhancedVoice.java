package snesible.core.apu.enhanced;

import snesible.core.apu.DspState;

/**
 * One of the eight voices, as the enhanced renderer plays it: a copy of the voice's decoded
 * samples, followed from the DSP, and the interpolation and mixing that turn them into sound.
 *
 * <h2>Following the DSP</h2>
 * After every DSP sample, {@link #capture} looks at the voice's ring of 12 decoded samples. If
 * the ring moved on, the DSP decoded 4 new BRR samples, and they're copied into a longer history
 * here (128 samples, enough for the widest anti-aliasing kernel). The renderer never decodes BRR
 * itself, so it can't disagree with the DSP about loop points, sample ends or BRR's filters.
 *
 * <p>The history is indexed by absolute sample number: {@code written} counts every sample ever
 * added, and the DSP's ring always holds the last 12 of them, oldest first. That's how a position
 * in the DSP's ring turns into a position here.
 *
 * <p>A key-on clears the DSP's ring and decodes the sample's first 4 values into it, so in
 * decoding order the ring then reads: 8 zeros, then those 4. The history gets exactly that added,
 * rather than being cleared, so the old note's samples stay available while the delayed output
 * (see {@link EnhancedRenderer}) is still playing its last moments.
 *
 * <h2>Position and pitch</h2>
 * The DSP interpolates each sample at the position it reached at the end of the previous sample.
 * So the position for this sample is the one saved last time, and the distance between the two
 * is the step the DSP actually took: its pitch, including pitch modulation. The renderer never
 * keeps a position of its own, so it can't drift away from the DSP.
 *
 * <h2>The queue</h2>
 * Output is delayed by {@link EnhancedRenderer#DELAY} samples, so that a wide kernel has the
 * samples after the output position. Each sample's state is kept in a small queue until it's
 * played.
 */
final class EnhancedVoice {

    private static final int HISTORY_SIZE = 128;
    private static final int HISTORY_MASK = HISTORY_SIZE - 1;

    /** De-click: how long a cut-off sound fades out, in samples (2 ms). */
    private static final int DECLICK_SAMPLES = 64;
    /** De-click: cuts smaller than this (in 16-bit units) are too small to click. */
    private static final float DECLICK_THRESHOLD = 64;

    // Per-voice register offsets (the voice's registers are at voice * $10 + offset).
    private static final int VOLL = 0x0;
    private static final int VOLR = 0x1;
    private static final int NON = 0x3D;

    private final int number;
    private final float[] history = new float[HISTORY_SIZE];
    /** How many samples have been added to the history so far. */
    private long written;
    /** The DSP ring position seen last time; a change means 4 new samples. */
    private int lastRingPosition;

    // Where the DSP will interpolate next, saved at the end of the previous sample.
    private long nextIndex;
    private int nextFraction;
    private int previousKeyOnDelay;

    // The queue, one entry per DSP sample.
    private final long[] index;
    private final int[] fraction;
    private final float[] step;
    private final boolean[] silent;
    private final int[] envelope;
    private final int[] volumeLeft;
    private final int[] volumeRight;
    private final boolean[] noise;
    private final int[] noiseValue;

    // De-click: the voice's last contribution, and a fade of a sound that was cut off.
    private float lastLeft;
    private float lastRight;
    private float fadeLeft;
    private float fadeRight;
    private int fadeRemaining;

    EnhancedVoice(int number, int queueLength) {
        this.number = number;
        index = new long[queueLength];
        fraction = new int[queueLength];
        step = new float[queueLength];
        silent = new boolean[queueLength];
        envelope = new int[queueLength];
        volumeLeft = new int[queueLength];
        volumeRight = new int[queueLength];
        noise = new boolean[queueLength];
        noiseValue = new int[queueLength];
    }

    /** Records this voice's part of the DSP sample just made, into queue entry {@code slot}. */
    void capture(DspState dsp, int slot) {
        int keyOnDelay = dsp.voiceKeyOnDelay(number);
        int ring = dsp.voiceRingPosition(number);
        // The delay counts down from KEY_ON_DELAY, and the sample with the key-on already took
        // one off. Nothing else gives this value, so it marks a key-on.
        boolean keyedOn = keyOnDelay == DspState.KEY_ON_DELAY - 1;
        if (keyedOn) {
            for (int i = 0; i < DspState.RING_SIZE - 4; i++) {
                add(0);
            }
            addFromRing(dsp, (ring + DspState.RING_SIZE - 4) % DspState.RING_SIZE);
        } else if (ring != lastRingPosition) {
            // At most one group of 4 per sample: the DSP's position can't move further.
            addFromRing(dsp, lastRingPosition);
        }
        lastRingPosition = ring;

        // The DSP's ring holds samples written-12 .. written-1, and its position counts from the
        // oldest, in 1/4096ths. Its Gaussian is centred one sample in from its first tap, so the
        // output position is (oldest + whole part + 1) plus the fraction.
        int position = dsp.voiceInterpolationPosition(number);
        long newIndex = written - DspState.RING_SIZE + (position >> 12) + 1;
        int newFraction = position & 0xFFF;

        index[slot] = nextIndex;
        fraction[slot] = nextFraction;
        step[slot] = ((newIndex - nextIndex) * 4096 + newFraction - nextFraction) / 4096f;
        // Silent while waiting after a key-on, including the sample in which the wait ended.
        silent[slot] = keyOnDelay > 0 || previousKeyOnDelay > 0;
        envelope[slot] = dsp.voiceEnvelope(number);
        volumeLeft[slot] = (byte) dsp.register((number << 4) | VOLL);
        volumeRight[slot] = (byte) dsp.register((number << 4) | VOLR);
        noise[slot] = (dsp.register(NON) & (1 << number)) != 0;
        noiseValue[slot] = dsp.noiseSample();

        nextIndex = newIndex;
        nextFraction = newFraction;
        previousKeyOnDelay = keyOnDelay;
    }

    /**
     * Adds this voice's sound for queue entry {@code slot} to {@code mix} {left, right}.
     *
     * @param table        the interpolator's weights (unused for the Gaussian)
     * @param antiAliasing stretch the kernel when the voice plays faster than recorded
     * @param surroundFix  use the size of each volume and ignore its sign
     * @param declick      fade out sounds that the hardware cuts off at once
     */
    void mixInto(float[] mix, int slot, Interpolator interpolator, KernelTable table, DspState dsp,
                 boolean antiAliasing, boolean surroundFix, boolean declick) {
        float left = 0;
        float right = 0;
        boolean sounding = !silent[slot] && envelope[slot] != 0;
        if (sounding) {
            float sample = noise[slot] ? noiseValue[slot] : interpolate(slot, interpolator, table, dsp, antiAliasing);
            // The hardware's envelope multiply is (sample × envelope) >> 11, and each volume
            // is a signed 8-bit multiplier >> 7. Here they're the same, without the rounding.
            float amplitude = sample * envelope[slot] / 2048f;
            int volLeft = surroundFix ? Math.abs(volumeLeft[slot]) : volumeLeft[slot];
            int volRight = surroundFix ? Math.abs(volumeRight[slot]) : volumeRight[slot];
            left = amplitude * volLeft / 128f;
            right = amplitude * volRight / 128f;
        }
        mix[0] += left;
        mix[1] += right;
        if (declick) {
            addDeclickFade(mix, sounding);
            lastLeft = left;
            lastRight = right;
        }
    }

    /**
     * De-click. The hardware cuts a voice to silence at once when a game restarts it (often to
     * play a sound effect on a voice the music was using) or when a sample ends without looping.
     * A jump from a loud value straight to zero is heard as a click. Here, when the voice stops
     * sounding (silenced, or its envelope at zero) right after a loud value, that value fades to
     * zero over 2 ms instead. A release, which ends quietly, doesn't need it.
     * The fade runs alongside the voice, so a new note can start at once, as on the hardware.
     */
    private void addDeclickFade(float[] mix, boolean sounding) {
        boolean cutOff = !sounding
                && (Math.abs(lastLeft) > DECLICK_THRESHOLD || Math.abs(lastRight) > DECLICK_THRESHOLD);
        if (cutOff) {
            // Carry on from any fade still running, so that doesn't jump either.
            float gain = fadeRemaining / (float) (DECLICK_SAMPLES + 1);
            fadeLeft = lastLeft + fadeLeft * gain;
            fadeRight = lastRight + fadeRight * gain;
            fadeRemaining = DECLICK_SAMPLES;
        }
        if (fadeRemaining > 0) {
            float gain = fadeRemaining / (float) (DECLICK_SAMPLES + 1);
            mix[0] += fadeLeft * gain;
            mix[1] += fadeRight * gain;
            fadeRemaining--;
        }
    }

    /** Forgets any de-click fade, for when the Quality output starts after the Hardware preset. */
    void resetDeclick() {
        lastLeft = 0;
        lastRight = 0;
        fadeRemaining = 0;
    }

    /** The voice's sample value at queue entry {@code slot}'s position. */
    float interpolate(int slot, Interpolator interpolator, KernelTable table, DspState dsp, boolean antiAliasing) {
        if (interpolator == Interpolator.GAUSSIAN) {
            // The hardware's taps are the sample before the output position's whole sample, it,
            // and the two after. The table has 256 steps, so the fraction's top 8 bits pick one.
            int weights = fraction[slot] >> 4;
            float sum = 0;
            for (int tap = 0; tap < 4; tap++) {
                sum += dsp.gaussianWeight(weights, tap) * history[(int) ((index[slot] - 1 + tap) & HISTORY_MASK)];
            }
            return sum / 2048f;
        }
        int band = antiAliasing ? KernelTable.bandFor(step[slot]) : 0;
        return table.interpolate(history, HISTORY_MASK, index[slot], fraction[slot], band);
    }

    private void addFromRing(DspState dsp, int start) {
        for (int i = 0; i < 4; i++) {
            add(dsp.voiceSample(number, (start + i) % DspState.RING_SIZE));
        }
    }

    private void add(int sample) {
        history[(int) (written & HISTORY_MASK)] = sample;
        written++;
    }
}
