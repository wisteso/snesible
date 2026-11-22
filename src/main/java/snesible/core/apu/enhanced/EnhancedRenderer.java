package snesible.core.apu.enhanced;

import snesible.core.apu.DspState;
import snesible.core.apu.SoundRenderer;

/**
 * Enhanced audio: makes a better-sounding version of what the DSP plays, in the style of the
 * SNESamp music player, without changing anything the console does.
 *
 * <h2>Why the console sounds the way it does</h2>
 * The DSP resamples each voice with a 4-point Gaussian curve, a gentle low-pass filter that is
 * a large part of the "muffled" SNES sound. It can't filter out the harsh aliases a sample makes
 * when it's played faster than it was recorded. And it mixes with 16-bit integers that round at
 * every step and clip when the sum gets too loud.
 *
 * <h2>What this does instead</h2>
 * <ul>
 *   <li><b>Interpolation:</b> a 16-point sinc (also in a minimum-phase version, which doesn't
 *       ring before sharp attacks) or cubic, which keep much more of a sample's treble
 *       ({@link Interpolator}).</li>
 *   <li><b>Anti-aliasing:</b> a voice playing faster than recorded uses a kernel stretched by its
 *       pitch step, which filters out what can't be reproduced ({@link KernelTable}).</li>
 *   <li><b>Floating-point mixing:</b> no rounding along the way, and no clipping until the very
 *       end, where a soft limiter rounds off peaks instead of cutting them flat.</li>
 *   <li><b>De-click:</b> fades out sounds the hardware cuts off at once ({@link EnhancedVoice}).</li>
 *   <li><b>Dither</b> when turning the result into 16-bit samples.</li>
 *   <li><b>Stereo width</b> and the <b>surround fix</b> ({@link AudioSettings}).</li>
 * </ul>
 * The output stays at the DSP's 32 kHz: one output sample per DSP sample.
 *
 * <h2>The DSP stays in charge</h2>
 * Games can see the DSP: they read its envelope and output registers, and the echo writes into
 * audio RAM. So the DSP runs exactly as always, and this only looks at it through
 * {@link DspState} after each sample. The echo is the DSP's own: its output is mixed in as the
 * hardware does. Noise voices play the DSP's noise value, whose sound comes from its 32 kHz rate.
 *
 * <h2>The delay</h2>
 * The 16-point sinc reads 8 samples after the output position (up to 32 when stretched for
 * anti-aliasing), but the DSP decodes only as it goes and is sometimes just 6 samples ahead. So
 * the Quality output runs {@link #DELAY} DSP samples (3 ms) behind, and by then the DSP has
 * decoded what's needed. Each voice, and the echo and volumes here, keep a queue of that length.
 *
 * <p>Why so long: a voice playing slowly decodes new samples slowly, and the delay has to cover
 * the slowest voice. 96 DSP samples covers a pitch of $0100, a sixteenth of a sample's recorded
 * speed. A voice slower still (rare) may need samples the DSP hasn't decoded yet; it reads its
 * newest sample in their place, a tiny error at such pitches. 3 ms more is hard to notice next
 * to the sound card's buffer (about 64 ms), and the Hardware preset isn't delayed.
 */
public final class EnhancedRenderer implements SoundRenderer {

    /** How many DSP samples the Quality output runs behind. */
    static final int DELAY = 96;
    private static final int QUEUE = DELAY + 1;

    /** Switching presets crossfades over this many samples (5 ms), so it doesn't click. */
    private static final int PRESET_FADE_SAMPLES = 160;

    /** The limiter leaves everything below -1 dB of full scale untouched. */
    private static final float LIMITER_THRESHOLD = 0.891f * 32767;

    // Global DSP registers.
    private static final int MVOLL = 0x0C;
    private static final int MVOLR = 0x1C;
    private static final int EVOLL = 0x2C;
    private static final int EVOLR = 0x3C;
    private static final int FLG = 0x6C;

    private final EnhancedVoice[] voices = new EnhancedVoice[8];

    // The queue of global state, one entry per DSP sample (see "The delay").
    private final int[] mainVolumeLeft = new int[QUEUE];
    private final int[] mainVolumeRight = new int[QUEUE];
    private final int[] echoVolumeLeft = new int[QUEUE];
    private final int[] echoVolumeRight = new int[QUEUE];
    private final int[] echoLeft = new int[QUEUE];
    private final int[] echoRight = new int[QUEUE];
    private final boolean[] muted = new boolean[QUEUE];
    private long samples;

    /** Set by the user interface's thread, read here on the emulation thread. */
    private volatile AudioSettings settings;
    /** The preset being played, and the one being faded out after a switch. */
    private AudioPreset playing;
    private AudioPreset fadingFrom;
    private int presetFadeRemaining;

    // For tests: these let a test check one part at a time.
    private Interpolator forcedInterpolator;
    private boolean antiAliasing = true;
    private boolean dither = true;
    private boolean declick = true;

    /** Dither's random numbers: a fixed sequence, so recordings come out the same every time. */
    private int random = 0x2545F491;

    // Working space, kept to avoid allocating 32,000 times a second.
    private final float[] mix = new float[2];
    private final float[] other = new float[2];
    private final float[] voiceMix = new float[2];

    public EnhancedRenderer(AudioSettings settings) {
        for (int v = 0; v < voices.length; v++) {
            voices[v] = new EnhancedVoice(v, QUEUE);
        }
        this.settings = settings;
        this.playing = settings.preset();
    }

    /** Changes the settings; a preset change crossfades. Safe to call from any thread. */
    public void setSettings(AudioSettings settings) {
        this.settings = settings;
    }

    public AudioSettings settings() {
        return settings;
    }

    /** Switches to the next preset (the preset key) and returns it. */
    public AudioPreset cyclePreset() {
        AudioSettings next = settings.withPreset(settings.preset().next());
        settings = next;
        return next.preset();
    }

    /**
     * For tests: renders the Quality mix with {@code interpolator} whatever the preset, and with
     * the chosen parts switched on or off.
     */
    void configureForTest(Interpolator interpolator, boolean antiAliasing, boolean dither, boolean declick) {
        this.forcedInterpolator = interpolator;
        this.antiAliasing = antiAliasing;
        this.dither = dither;
        this.declick = declick;
    }

    @Override
    public void afterSample(DspState dsp, int[] sample) {
        // Record this sample's state, then play the one DELAY samples ago: the oldest in the queue.
        int slot = (int) (samples % QUEUE);
        for (EnhancedVoice voice : voices) {
            voice.capture(dsp, slot);
        }
        mainVolumeLeft[slot] = (byte) dsp.register(MVOLL);
        mainVolumeRight[slot] = (byte) dsp.register(MVOLR);
        echoVolumeLeft[slot] = (byte) dsp.register(EVOLL);
        echoVolumeRight[slot] = (byte) dsp.register(EVOLR);
        echoLeft[slot] = dsp.echoOutput(0);
        echoRight[slot] = dsp.echoOutput(1);
        muted[slot] = (dsp.register(FLG) & 0x40) != 0;
        int playSlot = (int) ((samples + 1) % QUEUE);
        boolean queueFull = samples >= DELAY;
        samples++;

        AudioSettings now = settings;
        if (now.preset() != playing) {
            fadingFrom = playing;
            playing = now.preset();
            presetFadeRemaining = PRESET_FADE_SAMPLES;
            if (playing != AudioPreset.HARDWARE) {
                for (EnhancedVoice voice : voices) {
                    voice.resetDeclick();
                }
            }
        }
        boolean untouched = playing == AudioPreset.HARDWARE && presetFadeRemaining == 0
                && now.stereoWidth() == 100;
        if (untouched) {
            return; // the Hardware preset as it is: the DSP's own sample
        }

        render(playing, sample, dsp, playSlot, queueFull, now, declick, mix);
        if (presetFadeRemaining > 0) {
            // The preset being faded out leaves the voices' de-click alone: it's the new one's.
            render(fadingFrom, sample, dsp, playSlot, queueFull, now, false, other);
            float t = presetFadeRemaining / (float) (PRESET_FADE_SAMPLES + 1);
            mix[0] = mix[0] * (1 - t) + other[0] * t;
            mix[1] = mix[1] * (1 - t) + other[1] * t;
            presetFadeRemaining--;
        }
        output(mix, now.stereoWidth(), sample);
    }

    /** One preset's output for this sample, before the output stage, into {@code out}. */
    private void render(AudioPreset preset, int[] hardware, DspState dsp, int slot, boolean queueFull,
                        AudioSettings now, boolean useDeclick, float[] out) {
        if (preset == AudioPreset.HARDWARE) {
            out[0] = hardware[0];
            out[1] = hardware[1];
            return;
        }
        Interpolator interpolator = forcedInterpolator != null ? forcedInterpolator : preset.interpolator;
        boolean surroundFix = now.surroundFix();
        voiceMix[0] = 0;
        voiceMix[1] = 0;
        if (queueFull) {
            for (EnhancedVoice voice : voices) {
                voice.mixInto(voiceMix, slot, interpolator, dsp, antiAliasing, surroundFix, useDeclick);
            }
        }
        // As the hardware: the voices at the main volume, plus the echo at the echo volume.
        int mainLeft = mainVolumeLeft[slot];
        int mainRight = mainVolumeRight[slot];
        int echoVolLeft = echoVolumeLeft[slot];
        int echoVolRight = echoVolumeRight[slot];
        if (surroundFix) {
            mainLeft = Math.abs(mainLeft);
            mainRight = Math.abs(mainRight);
            echoVolLeft = Math.abs(echoVolLeft);
            echoVolRight = Math.abs(echoVolRight);
        }
        out[0] = voiceMix[0] * mainLeft / 128f + echoLeft[slot] * echoVolLeft / 128f;
        out[1] = voiceMix[1] * mainRight / 128f + echoRight[slot] * echoVolRight / 128f;
        if (muted[slot]) {
            out[0] = 0;
            out[1] = 0;
        }
    }

    /**
     * The output stage: stereo width, the soft limiter, dither, and rounding to 16 bits.
     *
     * <p><b>Stereo width</b> splits the sound into what both sides share (mid) and how they
     * differ (side), scales the side part, and puts them back together.
     */
    private void output(float[] stereo, int widthPercent, int[] sample) {
        float left = stereo[0];
        float right = stereo[1];
        if (widthPercent != 100) {
            float mid = (left + right) / 2;
            float side = (left - right) / 2 * (widthPercent / 100f);
            left = mid + side;
            right = mid - side;
        }
        sample[0] = toSixteenBits(limit(left));
        sample[1] = toSixteenBits(limit(right));
    }

    /**
     * The soft limiter. Below -1 dB of full scale nothing changes. Above it, a tanh curve bends
     * the rest smoothly towards full scale, so a peak that would clip is rounded off instead of
     * flattened, which sounds much less harsh. The curve starts with the same slope as the
     * straight part, so there's no kink where it begins.
     */
    static float limit(float x) {
        float size = Math.abs(x);
        if (size <= LIMITER_THRESHOLD) {
            return x;
        }
        float room = 32767 - LIMITER_THRESHOLD;
        float bent = LIMITER_THRESHOLD + room * (float) Math.tanh((size - LIMITER_THRESHOLD) / room);
        return Math.copySign(bent, x);
    }

    /**
     * Rounds to a 16-bit sample, with TPDF dither: a tiny random value (the sum of two uniform
     * ones, between -1 and +1 units) added first. Plain rounding makes its error follow the
     * signal, which sounds like faint distortion on quiet notes and fades; dither turns it into
     * an even, very quiet hiss instead.
     */
    private int toSixteenBits(float x) {
        if (dither) {
            x += nextRandom() + nextRandom() - 1;
        }
        return Math.max(-32768, Math.min(32767, Math.round(x)));
    }

    /** A random number from 0 to 1 (xorshift, a fast generator that's plenty for dither). */
    private float nextRandom() {
        random ^= random << 13;
        random ^= random >>> 17;
        random ^= random << 5;
        return (random >>> 8) / (float) (1 << 24);
    }
}
