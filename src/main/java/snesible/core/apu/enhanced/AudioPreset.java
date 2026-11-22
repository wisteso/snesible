package snesible.core.apu.enhanced;

/**
 * The sound presets, cycled with F10 in the windows.
 * <ul>
 *   <li>{@link #HARDWARE}: exactly what the console's DSP outputs.</li>
 *   <li>{@link #SINC_8}, {@link #SINC}, {@link #SINC_MINIMUM_PHASE} and {@link #CUBIC}: the
 *       enhanced renderer, with an 8-point sinc, a 16-point sinc, the 16-point sinc in minimum
 *       phase (no ringing before sharp attacks), or cubic interpolation. All get
 *       anti-aliasing, floating-point mixing, de-click, a soft limiter and dither (see
 *       {@link EnhancedRenderer}).</li>
 * </ul>
 * The user is comparing the three sincs by ear over a longer time (the 8-point one sounds
 * better in some cases, the 16-point in others), so all of them stay for now.
 * There are two Quality presets because games differ: many samples were recorded with extra
 * treble to make up for the hardware's softening. Sinc keeps the most of a sample's own treble and
 * filters aliasing hardest. Cubic keeps a little less of the treble but lets more aliasing through
 * at the very top (above about 12 kHz), which can sound "tinny"; it may still suit some games.
 */
public enum AudioPreset {

    // F10 cycles them in this order (the user's): the sincs from shortest to longest, then cubic.
    HARDWARE("Hardware", "hardware", null),
    SINC_8("Quality (sinc, 8-point)", "sinc8", Interpolator.SINC_8),
    SINC("Quality (sinc, 16-point)", "sinc16", Interpolator.SINC),
    SINC_MINIMUM_PHASE("Quality (sinc, 16-point, minimum phase)", "minphase", Interpolator.SINC_MINIMUM_PHASE),
    CUBIC("Quality (cubic)", "cubic", Interpolator.CUBIC);

    private final String label;
    private final String optionName;
    /** The interpolation the enhanced renderer uses, or null for the DSP's own output. */
    final Interpolator interpolator;

    AudioPreset(String label, String optionName, Interpolator interpolator) {
        this.label = label;
        this.optionName = optionName;
        this.interpolator = interpolator;
    }

    /** The name shown when switching, e.g. "Quality (sinc, 16-point)". */
    public String label() {
        return label;
    }

    /** The next preset, wrapping around: what the preset key switches to. */
    public AudioPreset next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /**
     * Parses a command-line name: {@code hardware}, {@code sinc8}, {@code sinc16},
     * {@code minphase} or {@code cubic}.
     */
    public static AudioPreset parse(String name) {
        for (AudioPreset preset : values()) {
            if (preset.optionName.equalsIgnoreCase(name)) {
                return preset;
            }
        }
        throw new IllegalArgumentException("Unknown audio preset: " + name + " (hardware, sinc8, sinc16, minphase or cubic)");
    }
}
