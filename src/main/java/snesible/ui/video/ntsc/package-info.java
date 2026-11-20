/*
 * Java port of snes_ntsc 0.2.2 by Shay Green (Blargg). Copyright (C) 2006-2007 Shay Green.
 * Licensed under the GNU Lesser General Public License, version 2.1 or later; see the
 * headers of the source files in this package.
 */

/**
 * An NTSC video filter: how the SNES's picture looked on a TV through a composite, S-Video or
 * RGB cable. This package is a Java port of Blargg's {@code snes_ntsc} library (as used by
 * Snes9x), and unlike the rest of Snesible it is licensed under the <b>GNU LGPL 2.1 or later</b>.
 * It depends only on the JDK and is kept separate so it can be replaced on its own.
 *
 * <h2>Why the SNES looks different on a TV</h2>
 * The SNES doesn't send a TV separate red, green and blue. Its video encoder (the S-ENC chip)
 * converts each pixel to <i>luma</i> (brightness, Y) and two <i>chroma</i> values (I and Q),
 * and in a composite signal adds them on one wire: chroma rides on a 3.58 MHz sine wave, the
 * color subcarrier, whose phase says the hue and amplitude says the saturation. The TV has to
 * pull them apart again with filters, and that separation is imperfect:
 * <ul>
 *   <li>Chroma has much less bandwidth than luma, so colors smear sideways (<i>bleed</i>).</li>
 *   <li>Fine brightness detail near the subcarrier frequency is mistaken for color, giving
 *       rainbow <i>artifacts</i>; and color changes leak into brightness (<i>fringing</i>).
 *       Dithered checkerboards, which games used for fake transparency, blend into a solid
 *       color this way.</li>
 *   <li>Luma itself is band-limited, so pixels soften (<i>resolution</i>, <i>sharpness</i>).</li>
 * </ul>
 *
 * <h2>The SNES's timing</h2>
 * The console's master clock (21.477 MHz) is exactly 6 times the subcarrier (3.5795 MHz). A
 * pixel lasts 4 master cycles, so <b>3 pixels span exactly 2 subcarrier cycles</b>: the artifact
 * pattern repeats every 3 pixels, and the filter works in chunks of 3 input pixels, which it
 * turns into 7 output pixels (256 pixels become 602). A scanline is 1364 master cycles, 227 1/3
 * subcarrier cycles, so each line starts 120 degrees further around: there are 3 "burst
 * phases", and the filter steps through them line by line. Each frame starts at a different
 * phase too; that's what makes composite video shimmer, and what field merging smooths over.
 *
 * <h2>How the filter is fast</h2>
 * Simulating the signal for every pixel of every frame would be slow. Instead, because the
 * process is linear, {@link snesible.ui.video.ntsc.SnesNtsc} works out once, for every possible input
 * color, the little smear of output pixels that color produces at each burst phase and each
 * position within a 3-pixel chunk. Filtering a frame is then just adding up the precomputed
 * smears of neighboring pixels.
 */
package snesible.ui.video.ntsc;
