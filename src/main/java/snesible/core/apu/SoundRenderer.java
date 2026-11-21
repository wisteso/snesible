package snesible.core.apu;

/**
 * Something that turns the DSP's work into the sound that's actually played, attached with
 * {@link Apu#setSoundRenderer}. Without one, the APU plays the DSP's own 32 kHz output.
 *
 * <p>It runs after each DSP sample and may only look at the DSP, never change it, so the console
 * behaves exactly the same with or without it.
 */
public interface SoundRenderer {

    /**
     * Called after each 32 kHz DSP sample.
     *
     * @param dsp    the DSP's state after this sample
     * @param sample the DSP's own output {left, right}; the renderer may replace both values
     *               (16-bit range) with what should be played instead
     */
    void afterSample(DspState dsp, int[] sample);
}
