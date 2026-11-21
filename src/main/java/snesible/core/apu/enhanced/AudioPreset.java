package snesible.core.apu.enhanced;

/**
 * The three sound presets, cycled with a key in the windows.
 * <ul>
 *   <li>{@link #HARDWARE}: exactly what the console's DSP outputs.</li>
 *   <li>{@link #SINC} and {@link #CUBIC}: the enhanced renderer, with sinc or cubic
 *       interpolation. Both also get anti-aliasing, floating-point mixing, de-click, a soft
 *       limiter and dither (see {@link EnhancedRenderer}).</li>
 * </ul>
 * There are two Quality presets because games differ: many samples were recorded with extra
 * treble to make up for the hardware's softening. Sinc keeps the most of a sample's own treble and
 * filters aliasing hardest. Cubic keeps a little less of the treble but lets more aliasing through
 * at the very top (above about 12 kHz), which can sound "tinny"; it may still suit some games.
 */
public enum AudioPreset {

    // F10 cycles them in this order. Sinc comes first: the user found it sounds better.
    HARDWARE("Hardware", "hardware", null),
    SINC("Quality (sinc)", "sinc", Interpolator.SINC),
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

    /** The name shown when switching, e.g. "Quality (sinc)". */
    public String label() {
        return label;
    }

    /** The next preset, wrapping around: what the preset key switches to. */
    public AudioPreset next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** Parses a command-line name: {@code hardware}, {@code sinc} or {@code cubic}. */
    public static AudioPreset parse(String name) {
        for (AudioPreset preset : values()) {
            if (preset.optionName.equalsIgnoreCase(name)) {
                return preset;
            }
        }
        throw new IllegalArgumentException("Unknown audio preset: " + name + " (hardware, sinc or cubic)");
    }
}
