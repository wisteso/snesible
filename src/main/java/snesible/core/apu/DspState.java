package snesible.core.apu;

/**
 * A read-only view of the DSP's internal state, for the enhanced sound renderer
 * ({@link snesible.core.apu.enhanced.EnhancedRenderer}).
 *
 * <p>The renderer makes its own, better-sounding version of what the DSP plays, but it must never
 * change what the DSP does: games read the DSP's registers, and its echo writes audio RAM. So
 * instead of hooks inside the DSP, it gets this view and looks at the DSP after each 32 kHz
 * sample. Everything here is a plain getter for state the DSP keeps anyway.
 */
public interface DspState {

    /** Each voice keeps its last 12 decoded BRR samples in a ring. */
    int RING_SIZE = 12;

    /**
     * Samples a keyed-on voice stays silent while the hardware fetches its first data. Right after
     * the sample in which a key-on happened, {@link #voiceKeyOnDelay} is one less than this.
     */
    int KEY_ON_DELAY = 5;

    /** DSP register {@code address} (0-$7F), as the SPC700 would read it. */
    int register(int address);

    /** Entry {@code index} (0-11) of a voice's ring of decoded samples (16-bit values). */
    int voiceSample(int voice, int index);

    /**
     * Where the voice's next 4 decoded samples will go in its ring. It's also the oldest entry:
     * reading the ring from here gives the samples in the order they were decoded.
     */
    int voiceRingPosition(int voice);

    /**
     * How far the voice is into its ring, in 1/4096ths of a sample, counted from the oldest
     * entry: 0 to $3FFF. The next sample interpolates around this point.
     */
    int voiceInterpolationPosition(int voice);

    /** The voice's envelope level, 0-$7FF. */
    int voiceEnvelope(int voice);

    /** Samples the voice still waits after a key-on before it plays (0 while playing). */
    int voiceKeyOnDelay(int voice);

    /** What a voice in noise mode (register NON) plays this sample, a 16-bit value. */
    int noiseSample();

    /** The echo's output after its FIR filter, before the echo volume: 0 = left, 1 = right. */
    int echoOutput(int channel);

    /**
     * The DSP's Gaussian interpolation weight for a fraction (0-255, in 1/256ths of a sample)
     * and tap (0-3). The four weights for one fraction add up to about 2048.
     */
    int gaussianWeight(int fraction, int tap);
}
