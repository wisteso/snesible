#version 330 core
/*
 * The filtering step of Blargg's snes_ntsc, on the GPU: one fragment per output pixel.
 *
 * Port of snes_ntsc 0.2.2 by Shay Green (Blargg), http://www.slack.net/~ant/
 * Copyright (C) 2006-2007 Shay Green. GLSL port for JFC, 2026. Licensed under the GNU Lesser
 * General Public License, version 2.1 or later (see jfc.ui.video.ntsc for the full notice).
 *
 * How it works (jfc.ui.video.ntsc.SnesNtsc explains the background):
 * - Setting up the filter simulates, once, how each of 8192 colors smears across neighboring
 *   output pixels after composite encoding and decoding. That table is computed in Java and
 *   arrives here as the ntscTable texture: one row per color, 128 columns = 3 burst phases x
 *   42 (3 positions within a 3-pixel chunk x 14 output pixels), each a packed RGB value.
 * - This shader does the per-frame part. Every 3 input pixels become 7 output pixels, and each
 *   output pixel is the sum of six nearby input pixels' smears, looked up in the table at the
 *   right column for this pixel's position and this line's burst phase.
 *
 * The arithmetic is the same unsigned 32-bit integer maths as SnesNtsc.blit (packed channels,
 * branch-free clamp), so the result is identical to the Java filter, pixel for pixel.
 */

uniform usampler2D picture;   // the PPU's raw pixels (brightness << 15 | bgr15), R32UI
uniform usampler2D ntscTable; // SnesNtsc.table(): 128 x 8192, R32UI
uniform int chunkCount;       // (input width - 1) / 3: full chunks per line (85 for 256)
uniform int framePhase;       // burst phase of the first line, 0-2 (frame number % 3)
uniform int scanlines;        // 1: darken every second output row by 1/8

out vec4 color;

// Packed RGB: red in bits 21-30, green in 11-20, blue in 1-10 (see SnesNtsc).
const uint RGB_BUILDER = (1u << 21) | (1u << 11) | (1u << 1);
const uint CLAMP_MASK = RGB_BUILDER * 3u / 2u;
const uint CLAMP_ADD = RGB_BUILDER * 0x101u;

int line;          // input line this output row comes from
int burstColumn;   // first table column for this line's burst phase

// The table row for input pixel i of this line: its color reduced to the filter's 13-bit index
// (4 bits red, 5 green, 4 blue) after master brightness, applied as Snes9x does:
// round(channel * brightness / 15). Pixels before the line and after its last chunk are black,
// which is how the original blitter starts and finishes each line.
uint tableRow(int i) {
    if (i < 0 || i > chunkCount * 3) {
        return 0u; // black
    }
    uint p = texelFetch(picture, ivec2(i, line), 0).r;
    uint brightness = (p >> 15u) & 15u;
    uint r = ((p & 31u) * brightness * 2u + 15u) / 30u;
    uint g = (((p >> 5u) & 31u) * brightness * 2u + 15u) / 30u;
    uint b = (((p >> 10u) & 31u) * brightness * 2u + 15u) / 30u;
    return ((r >> 1u) << 9u) | (g << 4u) | (b >> 1u);
}

uint smear(uint row, int column) {
    return texelFetch(ntscTable, ivec2(burstColumn + column, int(row)), 0).r;
}

void main() {
    int x = int(gl_FragCoord.x);       // output pixel, 0-601
    int outputRow = int(gl_FragCoord.y); // row 0 is the top of the picture
    line = outputRow / 2;               // each line is drawn twice
    burstColumn = ((framePhase + line) % 3) * 42; // the phase moves 120 degrees per line

    // Output pixel j of chunk n. The six input pixels that reach it, as the blitter's rolling
    // kernel0-2 and kernelx0-2 hold them at that moment: the newest three, and the three
    // before. Which ones are "newest" changes after output pixels 1 and 3.
    int n = x / 7;
    int j = x % 7;
    int base = 3 * n;
    uint k0 = tableRow(base + 1);
    uint k1 = tableRow(j < 2 ? base - 1 : base + 2);
    uint k2 = tableRow(j < 4 ? base : base + 3);
    uint kx0 = tableRow(base - 2);
    uint kx1 = tableRow(j < 2 ? base - 4 : base - 1);
    uint kx2 = tableRow(j < 4 ? base - 3 : base);

    uint raw = smear(k0, j) + smear(k1, (j + 12) % 7 + 14) + smear(k2, (j + 10) % 7 + 28)
             + smear(kx0, (j + 7) % 14) + smear(kx1, (j + 5) % 7 + 21) + smear(kx2, (j + 3) % 7 + 35);

    // Clamp each packed channel to 0-255 without branches (SnesNtsc.clamp, shift 1).
    uint sub = (raw >> 8u) & CLAMP_MASK;
    uint clampBits = CLAMP_ADD - sub;
    raw |= clampBits;
    clampBits -= sub;
    raw &= clampBits;
    uint rgb = ((raw >> 4u) & 0xFF0000u) | ((raw >> 2u) & 0xFF00u) | (raw & 0xFFu);

    if (scanlines != 0 && (outputRow & 1) == 1) {
        rgb -= (rgb >> 3u) & 0x1F1F1Fu; // each channel c - c/8, as Snes9x's NTSC scanlines
    }
    color = vec4(float((rgb >> 16u) & 255u), float((rgb >> 8u) & 255u), float(rgb & 255u), 255.0) / 255.0;
}
