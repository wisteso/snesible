package snesible.core.apu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Plays a tiny BRR sample on one voice and checks what comes out. */
class DspTest {

    private static final int DIRECTORY_PAGE = 0x10;
    private static final int SAMPLE_ADDRESS = 0x2000;

    private final byte[] ram = new byte[0x10000];
    private Dsp dsp;

    @BeforeEach
    void setUp() {
        // Sample directory entry 0: start and loop address both point at our one block.
        writeWord(DIRECTORY_PAGE << 8, SAMPLE_ADDRESS);
        writeWord((DIRECTORY_PAGE << 8) + 2, SAMPLE_ADDRESS);
        // One BRR block: range 12, filter 0, end and loop flags; 16 samples of +7.
        ram[SAMPLE_ADDRESS] = (byte) 0xC3;
        for (int i = 1; i <= 8; i++) {
            ram[SAMPLE_ADDRESS + i] = 0x77;
        }

        dsp = new Dsp(ram);
        dsp.write(0x5D, DIRECTORY_PAGE);  // DIR
        dsp.write(0x0C, 0x7F);            // MVOL L
        dsp.write(0x1C, 0x7F);            // MVOL R
        dsp.write(0x6C, 0x20);            // FLG: out of reset, not muted, echo writes off
        dsp.write(0x00, 0x7F);            // voice 0 VOL L
        dsp.write(0x01, 0x7F);            // voice 0 VOL R
        dsp.write(0x02, 0x00);            // voice 0 pitch $1000: the sample's own rate
        dsp.write(0x03, 0x10);
        dsp.write(0x04, 0x00);            // sample 0
        dsp.write(0x05, 0x8F);            // ADSR on, fastest attack
        dsp.write(0x06, 0xE0);            // sustain at the top, no sustain decay
    }

    @Test
    void silentUntilKeyedOn() {
        int[] out = new int[2];
        for (int i = 0; i < 10; i++) {
            dsp.generateSample(out);
        }
        assertEquals(0, out[0]);
    }

    @Test
    void keyedOnVoicePlaysItsSampleAfterTheKeyOnDelay() {
        dsp.write(0x4C, 0x01);            // KON voice 0
        int[] out = new int[2];
        for (int i = 0; i < 40; i++) {
            dsp.generateSample(out);
        }

        assertTrue(out[0] > 0, "a positive sample comes out: " + out[0]);
        assertEquals(out[0], out[1], "centered: same on both sides");
        assertNotEquals(0, dsp.read(0x08), "ENVX shows the envelope");
    }

    @Test
    void reachingTheEndOfTheSampleSetsEndx() {
        dsp.write(0x4C, 0x01);
        int[] out = new int[2];
        for (int i = 0; i < 40; i++) {
            dsp.generateSample(out);
        }

        assertEquals(0x01, dsp.read(0x7C) & 0x01);
    }

    @Test
    void keyOffReleasesTheVoice() {
        dsp.write(0x4C, 0x01);
        int[] out = new int[2];
        for (int i = 0; i < 40; i++) {
            dsp.generateSample(out);
        }
        dsp.write(0x5C, 0x01);            // KOF voice 0

        for (int i = 0; i < 600; i++) {   // release takes 8 per sample: 256 samples from full
            dsp.generateSample(out);
        }

        assertEquals(0, dsp.read(0x08), "envelope released to zero");
        assertEquals(0, out[0]);
    }

    private void writeWord(int address, int value) {
        ram[address] = (byte) value;
        ram[address + 1] = (byte) (value >> 8);
    }
}
