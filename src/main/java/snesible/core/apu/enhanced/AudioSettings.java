package snesible.core.apu.enhanced;

/**
 * The sound choices: a preset, plus two stereo options.
 *
 * @param preset       Hardware, or one of the Quality presets
 * @param stereoWidth  how wide the stereo image is, in percent: 100 is as the game mixes it,
 *                     0 is mono, 200 is twice as wide. Applies to every preset.
 * @param surroundFix  ignore the negative volumes games use for a fake "surround" effect, which
 *                     sounds hollow on headphones. Quality presets only: it needs each voice's
 *                     volume, which the Hardware preset's finished mix no longer has.
 */
public record AudioSettings(AudioPreset preset, int stereoWidth, boolean surroundFix) {

    /** The hardware's own sound, unchanged. */
    public static final AudioSettings DEFAULT = new AudioSettings(AudioPreset.HARDWARE, 100, false);

    public AudioSettings {
        if (stereoWidth < 0 || stereoWidth > 200) {
            throw new IllegalArgumentException("Stereo width must be 0-200%: " + stereoWidth);
        }
    }

    /** These settings with a different preset. */
    public AudioSettings withPreset(AudioPreset newPreset) {
        return new AudioSettings(newPreset, stereoWidth, surroundFix);
    }

    /**
     * Reads the sound options from a command line: {@code --audio <preset>},
     * {@code --stereo-width <percent>} and {@code --surround-fix}. Returns the settings and
     * leaves the other arguments, in order, in {@code rest}.
     */
    public static AudioSettings parse(String[] args, java.util.List<String> rest) {
        AudioSettings settings = DEFAULT;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--audio" -> settings = settings.withPreset(AudioPreset.parse(args[++i]));
                case "--stereo-width" -> settings = new AudioSettings(settings.preset,
                        Integer.parseInt(args[++i]), settings.surroundFix);
                case "--surround-fix" -> settings = new AudioSettings(settings.preset,
                        settings.stereoWidth, true);
                default -> rest.add(args[i]);
            }
        }
        return settings;
    }
}
