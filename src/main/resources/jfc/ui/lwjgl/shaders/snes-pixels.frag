#version 330 core
// Draws the PPU's raw output as sharp pixels.
//
// Each texel holds one SNES pixel exactly as the PPU produced it (see jfc.ui.video.SnesColor):
//   bits 18-15  master brightness (0-15)
//   bits 14-10  blue, bits 9-5 green, bits 4-0 red (5 bits each)
// The conversion to 8-bit RGB uses integer maths identical to SnesColor's lookup table, so
// this window shows exactly the same colors as the Swing window and the PNG screenshots.

uniform usampler2D picture;   // R32UI texture of packed pixels
uniform ivec2 pictureSize;    // the part of the texture in use: 256 x 224 (or 239)
uniform vec4 viewport;        // where the picture is drawn, in window pixels: x, y, width, height
uniform int flipY;            // 1: drawing to the window (OpenGL's row 0 is the bottom);
                              // 0: into an offscreen texture stored top line first

out vec4 color;

void main() {
    // Where this fragment falls in the picture, 0..1, with y running downwards like the SNES.
    vec2 position = (gl_FragCoord.xy - viewport.xy) / viewport.zw;
    if (flipY != 0) {
        position.y = 1.0 - position.y;
    }
    // texelFetch reads exactly one texel (no filtering), so pixels stay sharp.
    ivec2 texel = clamp(ivec2(position * vec2(pictureSize)), ivec2(0), pictureSize - 1);
    uint pixel = texelFetch(picture, texel, 0).r;

    uint brightness = (pixel >> 15u) & 15u;
    uvec3 color5 = uvec3(pixel & 31u, (pixel >> 5u) & 31u, (pixel >> 10u) & 31u);
    uvec3 color8 = (color5 << 3u) | (color5 >> 2u);     // copy the top bits down: 31 -> 255
    uvec3 lit = color8 * brightness / 15u;              // master brightness, rounded down
    color = vec4(vec3(lit) / 255.0, 1.0);
}
