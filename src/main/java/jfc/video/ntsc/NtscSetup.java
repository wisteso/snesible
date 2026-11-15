/*
 * Java port of snes_ntsc 0.2.2 by Shay Green (Blargg), http://www.slack.net/~ant/
 * Copyright (C) 2006-2007 Shay Green. Java port for JFC, 2026.
 *
 * This module is free software; you can redistribute it and/or modify it under the terms of
 * the GNU Lesser General Public License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version. This module is
 * distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details. You should have received a copy of the GNU Lesser
 * General Public License along with this module; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
package jfc.video.ntsc;

/**
 * The knobs of the NTSC filter: what kind of TV and cable we're imitating.
 *
 * <p>Every value runs from -1 to +1, with 0 the "typical" setting. The comments give the
 * internal meaning, as in Blargg's original header.
 *
 * @param hue         rotates every color: -1 = -180 degrees, +1 = +180 degrees
 * @param saturation  -1 = grayscale, +1 = twice the normal color
 * @param contrast    -1 = dark (0.5x), +1 = light (1.5x)
 * @param brightness  -1 = dark, +1 = light
 * @param sharpness   edge enhancement (positive) or blurring (negative) of the luma filter
 * @param gamma       -1 = dark (1.5), +1 = light (0.5); 0 maps a PC's 2.2 to a TV's ~2.65
 * @param resolution  luma bandwidth: how fine a brightness detail survives
 * @param artifacts   color "rainbows" caused where brightness changes (luma read as chroma)
 * @param fringing    color fringes at edges (chroma read as luma)
 * @param bleed       color bandwidth: how far colors smear sideways
 * @param mergeFields average neighboring color-burst phases, which removes the shimmer
 *                    ("dot crawl") that the phase moving every frame would otherwise cause
 */
public record NtscSetup(
        double hue, double saturation, double contrast, double brightness, double sharpness,
        double gamma, double resolution, double artifacts, double fringing, double bleed,
        boolean mergeFields) {

    /** A composite cable (the yellow plug): color bleeding and rainbow artifacts. */
    public static final NtscSetup COMPOSITE = new NtscSetup(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, true);

    /** S-Video: brightness and color travel on separate wires, so no artifacts, just bleeding. */
    public static final NtscSetup SVIDEO = new NtscSetup(0, 0, 0, 0, .2, 0, .2, -1, -1, 0, true);

    /** RGB (SCART): a crisp image with only a slight softening. */
    public static final NtscSetup RGB = new NtscSetup(0, 0, 0, 0, .2, 0, .7, -1, -1, -1, true);

    /** A black-and-white TV: no color, slight artifacts. */
    public static final NtscSetup MONOCHROME = new NtscSetup(0, -1, 0, 0, .2, 0, .2, -.2, -.2, -1, true);

    /**
     * Composite without field merging, as Snes9x's "RF" option: the dot-crawl shimmer stays
     * visible, as on an antenna connection.
     */
    public static final NtscSetup RF = new NtscSetup(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
}
