package jfc.ui.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The dynamic-rate-control resampler: exact at ratio 1, smooth and the right length otherwise. */
class AudioResamplerTest {

    private static final int CHUNK_FRAMES = 533; // about one NTSC frame of 32 kHz audio

    @Test
    void ratioOneReturnsTheInputOneSampleLater() {
        AudioResampler resampler = new AudioResampler();
        short[] out = new short[CHUNK_FRAMES * 2 + 8];
        short[] previous = {0, 0};
        for (int chunk = 0; chunk < 5; chunk++) {
            short[] in = stereoRamp(chunk * CHUNK_FRAMES, CHUNK_FRAMES);
            int count = resampler.process(in, in.length, 1.0, out);
            assertEquals(in.length, count);
            // The first output frame is the previous chunk's last; the rest are this chunk's.
            assertEquals(previous[0], out[0]);
            assertEquals(previous[1], out[1]);
            for (int i = 2; i < count; i++) {
                assertEquals(in[i - 2], out[i], "chunk " + chunk + " value " + i);
            }
            previous = new short[] {in[in.length - 2], in[in.length - 1]};
        }
    }

    @Test
    void aSlightlyHigherRatioMakesProportionallyMoreSamples() {
        AudioResampler resampler = new AudioResampler();
        short[] out = new short[CHUNK_FRAMES * 2 + 16];
        long totalIn = 0;
        long totalOut = 0;
        for (int chunk = 0; chunk < 600; chunk++) { // ten seconds
            short[] in = stereoRamp(0, CHUNK_FRAMES);
            totalIn += in.length;
            totalOut += resampler.process(in, in.length, 1.005, out);
        }
        double ratio = totalOut / (double) totalIn;
        assertEquals(1.005, ratio, 0.0002);
    }

    @Test
    void aSineWaveStaysSmoothAcrossChunkBoundaries() {
        // A 1 kHz tone at full scale changes by at most about 6,400 per sample at 32 kHz; a
        // click at a chunk boundary would show up as a much bigger jump.
        AudioResampler resampler = new AudioResampler();
        short[] out = new short[CHUNK_FRAMES * 2 + 16];
        int t = 0;
        int last = 0;
        int biggestStep = 0;
        for (int chunk = 0; chunk < 60; chunk++) {
            short[] in = new short[CHUNK_FRAMES * 2];
            for (int i = 0; i < CHUNK_FRAMES; i++, t++) {
                short v = (short) Math.round(32000 * Math.sin(2 * Math.PI * 1000 * t / 32000.0));
                in[i * 2] = v;
                in[i * 2 + 1] = v;
            }
            double ratio = chunk % 2 == 0 ? 0.995 : 1.005;
            int count = resampler.process(in, in.length, ratio, out);
            for (int i = 0; i < count; i += 2) {
                if (chunk > 0 || i > 0) {
                    biggestStep = Math.max(biggestStep, Math.abs(out[i] - last));
                }
                last = out[i];
            }
        }
        assertTrue(biggestStep < 6600, "largest step " + biggestStep);
    }

    /** Stereo frames whose left value counts up from {@code start} and right counts down. */
    private static short[] stereoRamp(int start, int frames) {
        short[] samples = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            samples[i * 2] = (short) (start + i);
            samples[i * 2 + 1] = (short) -(start + i);
        }
        return samples;
    }
}
