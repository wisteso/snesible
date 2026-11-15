package jfc.frontend;

import java.util.Optional;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import jfc.apu.Apu;

/**
 * Plays the DSP's output through Java Sound ({@code javax.sound.sampled}).
 *
 * <p>The sound card consumes exactly 32,000 samples a second, and writing blocks while its buffer
 * is full. That makes it a precise clock: if the emulator writes each frame's audio and then
 * carries on, it naturally runs at the console's real speed, and sound and picture can't drift
 * apart. The buffer is kept short (about 4 frames), because everything in it is latency.
 */
public final class AudioOutput implements AutoCloseable {

    private static final int BUFFER_BYTES = 8192;

    private final SourceDataLine line;
    private byte[] bytes = new byte[0];

    private AudioOutput(SourceDataLine line) {
        this.line = line;
    }

    /** Opens the default sound device, or returns empty if there isn't one. */
    public static Optional<AudioOutput> open() {
        AudioFormat format = new AudioFormat(Apu.SAMPLE_RATE, 16, 2, true, false);
        try {
            SourceDataLine line = AudioSystem.getSourceDataLine(format);
            line.open(format, BUFFER_BYTES);
            line.start();
            return Optional.of(new AudioOutput(line));
        } catch (LineUnavailableException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Plays interleaved left/right samples, waiting while the device's buffer is full.
     *
     * @param count number of 16-bit values (twice the number of stereo samples)
     */
    public void play(short[] samples, int count) {
        if (bytes.length < count * 2) {
            bytes = new byte[count * 2];
        }
        for (int i = 0; i < count; i++) {
            bytes[i * 2] = (byte) samples[i];             // little-endian
            bytes[i * 2 + 1] = (byte) (samples[i] >> 8);
        }
        line.write(bytes, 0, count * 2);
    }

    @Override
    public void close() {
        line.stop();
        line.close();
    }
}
