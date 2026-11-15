package jfc.ui.video;

import java.util.Locale;
import jfc.ui.video.ntsc.NtscSetup;

/**
 * How the picture is shown: as sharp pixels, or through the NTSC filter imitating a TV and a
 * particular cable. The NTSC modes match Snes9x's "Blargg NTSC" filter options.
 */
public enum VideoMode {

    /** Each SNES pixel as a crisp square, as an emulator usually shows it. */
    SHARP("sharp pixels", null),
    /** Composite cable: color bleeding and rainbow artifacts. */
    COMPOSITE("NTSC composite", NtscSetup.COMPOSITE),
    /** S-Video: color bleeding only. */
    SVIDEO("NTSC S-Video", NtscSetup.SVIDEO),
    /** RGB: crisp, slightly softened. */
    RGB("NTSC RGB", NtscSetup.RGB),
    /** Black-and-white TV. */
    MONOCHROME("NTSC monochrome", NtscSetup.MONOCHROME),
    /** Composite with visible dot crawl (no field merging). */
    RF("NTSC RF", NtscSetup.RF);

    private final String label;
    private final NtscSetup ntscSetup;

    VideoMode(String label, NtscSetup ntscSetup) {
        this.label = label;
        this.ntscSetup = ntscSetup;
    }

    /** A name for messages, like "NTSC composite". */
    public String label() {
        return label;
    }

    /** The NTSC filter settings, or null for {@link #SHARP}. */
    public NtscSetup ntscSetup() {
        return ntscSetup;
    }

    /** The next mode, for cycling with a key. */
    public VideoMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** Parses a command-line name such as {@code composite} or {@code svideo}. */
    public static VideoMode parse(String name) {
        return valueOf(name.toUpperCase(Locale.ROOT).replace("-", ""));
    }
}
