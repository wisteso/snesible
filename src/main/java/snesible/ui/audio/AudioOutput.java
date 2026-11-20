package snesible.ui.audio;

import java.util.Optional;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import snesible.core.apu.Apu;

/**
 * Plays the DSP's output through Java Sound ({@code javax.sound.sampled}), keeping the sound
 * card's buffer about half full with <i>dynamic rate control</i>.
 *
 * <h2>Why the sound card doesn't set the pace</h2>
 * The sound card consumes exactly 32,000 samples a second, and a write blocks while its buffer
 * is full, so an emulator that writes each frame's audio and carries on runs at precisely the
 * right speed. Snesible used to work that way. The catch: the device frees buffer space in chunks
 * (about 30 ms on Windows), so the write sometimes blocked for two frames' worth of time and
 * the next picture arrived late, 35 ms after the previous one (measured). And a window that
 * syncs to the display has to follow the display's clock, not the sound card's.
 *
 * <h2>What happens instead</h2>
 * Something else paces the frames (a steady {@link snesible.ui.FrameClock}, or the display's
 * vsync), and this class makes the sound fit: it watches how full the device's buffer is and
 * resamples each frame's audio by a tiny ratio, at most {@link #MAX_RATIO_CHANGE} (0.5%):
 * slightly more samples when the buffer is emptier than half, slightly fewer when fuller. That
 * absorbs the chunky draining, the difference between the PC's clock and the sound card's, and
 * a display that runs at 60.00 Hz instead of the console's 60.10. A 0.5% pitch change is far
 * below what anyone can hear. RetroArch calls this technique dynamic rate control.
 *
 * <p>Writes then normally return at once. If the buffer is ever too full for a frame's audio
 * (the frames came faster than real time), the part that doesn't fit is dropped rather than
 * waiting, because waiting would stall the picture.
 */
public final class AudioOutput implements AutoCloseable {

    /** The device buffer: 4096 stereo frames (16-bit), 128 ms. We aim to keep it half full. */
    private static final int BUFFER_BYTES = 4096 * 4;
    private static final int BYTES_PER_FRAME = 4;

    /** The most the playback rate is ever adjusted, as a fraction (0.005 = 0.5%). */
    private static final double MAX_RATIO_CHANGE = 0.005;

    /**
     * How quickly the measured fill level follows the device: each frame moves the average a
     * tenth of the way. The device reports its level in coarse steps, so we smooth it.
     */
    private static final double FILL_SMOOTHING = 0.1;

    /**
     * Below this fill level the buffer is about to run dry (the frames stopped for a moment:
     * the window was dragged, the display was being measured). Rather than creep back up at
     * 0.5% for seconds, it's topped up with silence at once; a gap that long is silent anyway.
     */
    private static final double REFILL_BELOW = 0.1;

    private final SourceDataLine line;
    private final int capacity;
    private final AudioResampler resampler = new AudioResampler();
    private short[] resampled = new short[0];
    private byte[] bytes = new byte[0];
    private double averageFill = 0.5;
    private double ratio = 1.0;
    private long droppedBytes;

    private AudioOutput(SourceDataLine line) {
        this.line = line;
        this.capacity = line.getBufferSize();
        // Start half full of silence, the level rate control aims for, so it doesn't spend the
        // first seconds slowly filling an empty buffer (and stuttering meanwhile).
        int half = capacity / 2 / BYTES_PER_FRAME * BYTES_PER_FRAME;
        line.write(new byte[half], 0, half);
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
     * Plays interleaved left/right samples, stretched or squeezed slightly to keep the device
     * buffer half full. Returns at once.
     *
     * @param count number of 16-bit values (twice the number of stereo samples)
     */
    public void play(short[] samples, int count) {
        // How full is the device's buffer (0 = empty, 1 = full)? Smoothed over a few frames.
        int free = line.available();
        double fill = 1.0 - free / (double) capacity;
        if (fill < REFILL_BELOW) {
            int silence = (int) ((0.5 - fill) * capacity) / BYTES_PER_FRAME * BYTES_PER_FRAME;
            line.write(new byte[silence], 0, silence);
            free -= silence;
            fill = 0.5;
            averageFill = 0.5;
        }
        averageFill += (fill - averageFill) * FILL_SMOOTHING;
        // Below half full: make a little more sound (ratio above 1); above half: a little less.
        ratio = 1.0 + MAX_RATIO_CHANGE * Math.max(-1, Math.min(1, (0.5 - averageFill) * 2));

        int needed = (int) (count * ratio) + 8;
        if (resampled.length < needed) {
            resampled = new short[needed];
            bytes = new byte[needed * 2];
        }
        int outCount = resampler.process(samples, count, ratio, resampled);
        for (int i = 0; i < outCount; i++) {
            bytes[i * 2] = (byte) resampled[i];             // little-endian
            bytes[i * 2 + 1] = (byte) (resampled[i] >> 8);
        }
        int length = outCount * 2;
        int fits = Math.min(length, free / BYTES_PER_FRAME * BYTES_PER_FRAME);
        droppedBytes += length - fits;
        line.write(bytes, 0, fits);
    }

    /** The smoothed buffer level, 0 (empty) to 1 (full); about 0.5 when all is well. */
    public double averageFill() {
        return averageFill;
    }

    /** The resampling ratio used for the last frame (1 = unchanged). */
    public double ratio() {
        return ratio;
    }

    /** Bytes of sound thrown away so far because the buffer was full. */
    public long droppedBytes() {
        return droppedBytes;
    }

    @Override
    public void close() {
        line.stop();
        line.close();
    }
}
