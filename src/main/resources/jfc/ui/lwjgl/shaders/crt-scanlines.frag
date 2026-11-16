#version 330 core
// Scanlines as a CRT draws them: each SNES line is a horizontal beam of light, and the dark
// gaps between lines are simply where no beam passes.
//
// How a CRT makes scanlines: an electron beam sweeps each line, and the phosphor glows where
// it hits. Across the line, the beam's intensity falls off smoothly from its center (roughly a
// Gaussian bell). A brighter pixel means a stronger beam, and a stronger beam also spreads
// wider, so bright lines fill the gaps between them while dark lines stay thin with clear
// gaps. That brightness-dependent width is what makes CRT scanlines look alive rather than
// like a fixed pattern of dark stripes.
//
// The SNES normally sends 240p, not interlaced video: every field puts its 262 lines in the
// same place on the tube, so the gaps don't move from frame to frame. (Interlace mode, which
// very few games use and JFC doesn't emulate yet, would shift alternate fields by half a line.)
//
// For each window pixel, this shader finds the two SNES lines whose centers are nearest above
// and below, and adds their two beams' light at this distance. The adding happens in linear
// light (gamma decoded), because that's how light from overlapping beams combines.

uniform sampler2D picture;   // RGB picture, top line first, rowsPerLine rows per SNES line,
                             // linear filtering
uniform vec4 viewport;       // where the picture is drawn, in window pixels: x, y, width, height
uniform int lineCount;       // SNES lines in the picture: 224 (or 239 with overscan)
uniform int rowsPerLine;     // texture rows per line: 2 for the NTSC picture, 1 for sharp pixels
uniform int smoothX;         // 1: blend neighboring pixels sideways (NTSC); 0: sharp pixels

out vec4 color;

// The picture's values are gamma-encoded for a PC monitor (about 2.2).
const float GAMMA = 2.2;
// The beam's width (the Gaussian's sigma) in line heights, for a black and a full-brightness
// pixel. At 0.24 two dark beams meet at ~27% of their peak; at 0.40 bright ones at ~90%, so
// the gaps all but close.
const float DARK_WIDTH = 0.24;
const float BRIGHT_WIDTH = 0.40;

// The linear-light color of a SNES line at horizontal position x (0..1). Lines outside the
// picture are black, as above and below the picture on a TV.
vec3 lineColor(int line, float x) {
    if (line < 0 || line >= lineCount) {
        return vec3(0.0);
    }
    vec2 size = vec2(textureSize(picture, 0));
    // Sharp pixels: snap to the texel's center so the linear filter doesn't blend sideways.
    float u = smoothX != 0 ? x : (floor(x * size.x) + 0.5) / size.x;
    // The line's first row, at its center, so lines aren't blended into each other.
    float v = (float(line * rowsPerLine) + 0.5) / size.y;
    return pow(texture(picture, vec2(u, v)).rgb, vec3(GAMMA));
}

// The light a beam of this color puts at this distance from its center (in line heights).
// Each color channel is its own beam (the tube has three electron guns), so a saturated
// color's strong channel spreads wider than its weak ones.
vec3 beam(vec3 light, float distance) {
    vec3 width = mix(vec3(DARK_WIDTH), vec3(BRIGHT_WIDTH), sqrt(light));
    return light * exp(-0.5 * (distance * distance) / (width * width));
}

void main() {
    // Where this fragment falls in the picture, 0..1, with y running downwards like the SNES.
    vec2 position = (gl_FragCoord.xy - viewport.xy) / viewport.zw;
    position.y = 1.0 - position.y;

    // Measured in lines, with 0 at the center of line 0: the line above (or at) this pixel is
    // floor(y), and the pixel lies the fraction d of the way to the next line's center.
    float y = position.y * float(lineCount) - 0.5;
    float above = floor(y);
    float d = y - above;
    int line = int(above);

    vec3 light = beam(lineColor(line, position.x), d)
               + beam(lineColor(line + 1, position.x), 1.0 - d);
    color = vec4(pow(min(light, vec3(1.0)), vec3(1.0 / GAMMA)), 1.0);
}
