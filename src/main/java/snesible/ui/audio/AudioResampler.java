package snesible.ui.audio;

/**
 * Stretches or squeezes a stream of stereo samples by a small ratio, by linear interpolation.
 *
 * <p>Used for dynamic rate control (see {@link AudioOutput}): to keep the sound card's buffer
 * from slowly filling up or running dry, each frame's audio is played very slightly faster or
 * slower, by at most a fraction of a percent, which nobody can hear.
 *
 * <p>The input is treated as one continuous stream across calls: the last stereo frame of each
 * call is kept and used to interpolate the start of the next, so chunk boundaries don't click.
 * (The output therefore runs one sample behind the input.)
 */
final class AudioResampler {

    /**
     * Where the next output sample falls, in input frames, relative to the first frame of the
     * next input chunk. -1 means "exactly at the last frame of the previous chunk".
     */
    private double position = -1;
    private short previousLeft;
    private short previousRight;

    /**
     * Resamples one chunk.
     *
     * @param in    interleaved left/right samples
     * @param count number of 16-bit values in {@code in} (twice the stereo frames)
     * @param ratio output frames per input frame: 1 keeps the speed, 1.005 makes 0.5% more
     * @param out   receives interleaved samples; needs room for {@code count * ratio + 4}
     * @return number of 16-bit values written to {@code out}
     */
    int process(short[] in, int count, double ratio, short[] out) {
        int frames = count / 2;
        if (frames == 0) {
            return 0;
        }
        double step = 1.0 / ratio; // input frames per output frame
        int written = 0;
        double t = position;
        // Output positions run from the previous chunk's last frame (index -1) up to, but not
        // including, this chunk's last frame, so every output has an input frame on each side.
        while (t < frames - 1) {
            int i = (int) Math.floor(t);
            double frac = t - i;
            int left0 = i < 0 ? previousLeft : in[i * 2];
            int right0 = i < 0 ? previousRight : in[i * 2 + 1];
            int left1 = in[(i + 1) * 2];
            int right1 = in[(i + 1) * 2 + 1];
            out[written++] = (short) Math.round(left0 + (left1 - left0) * frac);
            out[written++] = (short) Math.round(right0 + (right1 - right0) * frac);
            t += step;
        }
        // This chunk's last frame becomes frame -1 of the next chunk.
        position = t - frames;
        previousLeft = in[(frames - 1) * 2];
        previousRight = in[(frames - 1) * 2 + 1];
        return written;
    }
}
