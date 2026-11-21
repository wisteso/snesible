package snesible.core.apu;

/**
 * Test support: a bare DSP over a RAM array, for tests in other packages (the DSP itself is
 * package-private). Tests of the enhanced renderer use it to play hand-made samples.
 */
public final class TestDsp {

    private final Dsp dsp;

    public TestDsp(byte[] ram) {
        this.dsp = new Dsp(ram);
    }

    /** Writes a DSP register, as the SPC700 would through $F2/$F3. */
    public void write(int register, int value) {
        dsp.write(register, value);
    }

    /** Produces one stereo sample into {@code out}. */
    public void generateSample(int[] out) {
        dsp.generateSample(out);
    }

    /** The DSP's read-only view, as a sound renderer sees it. */
    public DspState state() {
        return dsp;
    }
}
