package snesible.core.apu.enhanced;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import snesible.core.apu.TestDsp;

/**
 * The enhanced renderer's mixing and output stage, on one voice playing a hand-made sample: a
 * loud looping triangle wave, which changes by at most a few thousand units per sample, so any
 * large jump in the output is a click.
 */
class EnhancedRendererTest {

    private static final int DIRECTORY_PAGE = 0x10;
    private static final int SAMPLE_ADDRESS = 0x2000;

    private final byte[] ram = new byte[0x10000];
    private TestDsp dsp;
    private final int[] sample = new int[2];

    @BeforeEach
    void setUp() {
        writeWord(DIRECTORY_PAGE << 8, SAMPLE_ADDRESS);        // sample 0 starts here
        writeWord((DIRECTORY_PAGE << 8) + 2, SAMPLE_ADDRESS);  // and loops back here
        // Two BRR blocks (range 12, no filter): up and down from 0 to +7, then to -7 and back.
        byte[] blocks = {
            (byte) 0xC0, 0x01, 0x23, 0x45, 0x67, 0x76, 0x54, 0x32, 0x10,
            (byte) 0xC3, 0x0F, (byte) 0xED, (byte) 0xCB, (byte) 0xA9,
            (byte) 0x9A, (byte) 0xBC, (byte) 0xDE, (byte) 0xF0,
        };
        System.arraycopy(blocks, 0, ram, SAMPLE_ADDRESS, blocks.length);

        dsp = new TestDsp(ram);
        dsp.write(0x5D, DIRECTORY_PAGE);  // DIR
        dsp.write(0x0C, 0x7F);            // MVOL L/R
        dsp.write(0x1C, 0x7F);
        dsp.write(0x6C, 0x20);            // FLG: out of reset, not muted, echo writes off
        dsp.write(0x00, 0x7F);            // voice 0 VOL L/R
        dsp.write(0x01, 0x7F);
        dsp.write(0x02, 0x00);            // pitch $1000: the sample's own rate
        dsp.write(0x03, 0x10);
        dsp.write(0x04, 0x00);            // sample 0
        dsp.write(0x05, 0x8F);            // ADSR, fastest attack
        dsp.write(0x06, 0xE0);            // sustain at the top
    }

    /**
     * Restarting a voice while it's loud (as a game does to play a sound effect on it) cuts it
     * to silence at once on the hardware: a click. De-click fades it out instead.
     */
    @Test
    void deClickSmoothsARestartedVoice() {
        assertTrue(largestJumpAroundARestart(false) > 15000, "the hardware's cut is a big jump");
        assertTrue(largestJumpAroundARestart(true) < 5000, "faded with de-click");
    }

    private int largestJumpAroundARestart(boolean declick) {
        setUp();
        EnhancedRenderer renderer = new EnhancedRenderer(AudioSettings.DEFAULT.withPreset(AudioPreset.SINC));
        renderer.configureForTest(Interpolator.SINC, true, false, declick);
        dsp.write(0x4C, 0x01);            // key on
        // Play until the wave is near its peak, then key on again.
        int previous = 0;
        int largest = 0;
        boolean restarted = false;
        for (int i = 0; i < 400; i++) {
            if (!restarted && i > 100 && previous > 20000) {
                dsp.write(0x4C, 0x01);
                restarted = true;
            }
            int left = next(renderer);
            largest = Math.max(largest, Math.abs(left - previous));
            previous = left;
        }
        assertTrue(restarted);
        return largest;
    }

    /** Switching presets while a note plays crossfades, so it doesn't click. */
    @Test
    void switchingPresetsDoesNotClick() {
        EnhancedRenderer renderer = new EnhancedRenderer(AudioSettings.DEFAULT);
        dsp.write(0x4C, 0x01);
        int previous = 0;
        int largest = 0;
        for (int i = 0; i < 300 * (AudioPreset.values().length + 1); i++) {
            if (i % 300 == 0 && i > 0) {
                renderer.cyclePreset(); // through every Quality preset and back to hardware
            }
            int left = next(renderer);
            if (i > 50) {
                largest = Math.max(largest, Math.abs(left - previous));
            }
            previous = left;
        }
        assertEquals(AudioPreset.HARDWARE, renderer.settings().preset());
        assertTrue(largest < 6000, "largest jump " + largest);
    }

    /** Many games make "surround" by giving one side a negative volume; the fix undoes that. */
    @Test
    void theSurroundFixMakesBothSidesTheSameSign() {
        dsp.write(0x01, 0x81);            // voice 0 VOL R: -127
        EnhancedRenderer plain = new EnhancedRenderer(new AudioSettings(AudioPreset.SINC, 100, false));
        assertTrue(oppositeSigns(plain), "without the fix, the sides are inverted");

        setUp();
        dsp.write(0x01, 0x81);
        EnhancedRenderer fixed = new EnhancedRenderer(new AudioSettings(AudioPreset.SINC, 100, true));
        assertTrue(!oppositeSigns(fixed), "with the fix, both sides match");
    }

    private boolean oppositeSigns(EnhancedRenderer renderer) {
        dsp.write(0x4C, 0x01);
        for (int i = 0; i < EnhancedRenderer.DELAY + 100; i++) {
            next(renderer);
        }
        return Integer.signum(sample[0]) == -Integer.signum(sample[1]) && sample[0] != 0;
    }

    /** A stereo width of 0 makes mono, in every preset. */
    @Test
    void zeroStereoWidthIsMono() {
        dsp.write(0x01, 0x20);            // voice 0 mostly on the left
        for (AudioPreset preset : AudioPreset.values()) {
            setUp();
            dsp.write(0x01, 0x20);
            EnhancedRenderer renderer = new EnhancedRenderer(new AudioSettings(preset, 0, false));
            dsp.write(0x4C, 0x01);
            for (int i = 0; i < 200; i++) {
                next(renderer);
                assertEquals(sample[0], sample[1], 2, preset + ": dither may differ by a unit or two");
            }
        }
    }

    @Test
    void theLimiterOnlyBendsPeaks() {
        assertEquals(12345f, EnhancedRenderer.limit(12345f));
        assertEquals(-29000f, EnhancedRenderer.limit(-29000f));
        float previous = 0;
        for (float x = 0; x < 200000; x += 50) {
            float y = EnhancedRenderer.limit(x);
            assertTrue(y >= previous, "never decreases");
            assertTrue(y < 32767.5f, "never past full scale");
            assertEquals(-y, EnhancedRenderer.limit(-x), 0.001f);
            previous = y;
        }
    }

    /** One DSP sample through the renderer; returns the left channel. */
    private int next(EnhancedRenderer renderer) {
        dsp.generateSample(sample);
        renderer.afterSample(dsp.state(), sample);
        return sample[0];
    }

    private void writeWord(int address, int value) {
        ram[address] = (byte) value;
        ram[address + 1] = (byte) (value >> 8);
    }
}
