package jfc.ui.debug;

import jfc.debug.Access;

/**
 * How the heat map turns access stamps into colours.
 *
 * <p>Each access kind has a colour, and they <b>add up</b> like light. The three processor
 * kinds are the three primaries, so mixes read naturally:
 * <ul>
 *   <li>read = green, write = red, execute = blue;</li>
 *   <li>code is fetched (blue) and its operands also read now and then (green): cyan;</li>
 *   <li>data both read and written: yellow; DMA: magenta; PPU/DSP (derived): grey.</li>
 * </ul>
 *
 * <p>A byte lights up fully when it's accessed and fades over {@link #FADE_FRAMES} frames
 * (1.5 s) to a dim glow that stays, so memory that was ever used remains faintly visible while
 * the current activity stands out.
 */
final class HeatPalette {

    /** Frames over which an access fades from full brightness to the glow. */
    static final int FADE_FRAMES = 90;

    /** Brightness (out of 256) left after the fade, for "accessed at some point". */
    private static final int GLOW = 56;

    /** Untouched memory. */
    static final int NEVER = 0x101014;

    private static final int[] KIND_RGB = {
            0x00E000,   // READ: green
            0xF02010,   // WRITE: red
            0x2060FF,   // EXECUTE: blue
            0xE000E0,   // DMA: magenta
            0x909090,   // CHIP: grey
    };

    /** Brightness (0-256) by age in frames, 0 to {@link #FADE_FRAMES}. */
    private static final int[] FADE = new int[FADE_FRAMES + 1];

    static {
        for (int age = 0; age <= FADE_FRAMES; age++) {
            // Square the remaining time so a byte drops quickly at first and then lingers,
            // which keeps bursts readable without smearing everything.
            double left = 1.0 - age / (double) FADE_FRAMES;
            FADE[age] = GLOW + (int) Math.round((256 - GLOW) * left * left);
        }
    }

    private HeatPalette() {
    }

    /** The legend colour of one kind. */
    static int color(Access kind) {
        return KIND_RGB[kind.ordinal()];
    }

    /** Brightness 0-256 for an access {@code age} frames ago (0 if the stamp is 0, never). */
    static int brightness(int stamp, int now) {
        if (stamp == 0) {
            return 0;
        }
        int age = now - stamp;
        return age >= FADE_FRAMES ? GLOW : FADE[Math.max(0, age)];
    }

    /**
     * Mixes the kinds into one colour: {@code stamps[k]} is the newest frame kind {@code k}
     * happened (0 = never).
     */
    static int mix(int[] stamps, int now) {
        int r = 0;
        int g = 0;
        int b = 0;
        boolean any = false;
        for (int k = 0; k < stamps.length; k++) {
            int level = brightness(stamps[k], now);
            if (level == 0) {
                continue;
            }
            any = true;
            int rgb = KIND_RGB[k];
            r += ((rgb >> 16) & 0xFF) * level >> 8;
            g += ((rgb >> 8) & 0xFF) * level >> 8;
            b += (rgb & 0xFF) * level >> 8;
        }
        if (!any) {
            return NEVER;
        }
        return Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
    }
}
