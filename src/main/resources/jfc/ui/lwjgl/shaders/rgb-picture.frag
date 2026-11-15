#version 330 core
// Draws an already-filtered RGB picture (the Java NTSC filter's 602 x 448 output), scaled to
// the viewport with bilinear filtering. The NTSC picture already has fine horizontal detail,
// so smooth scaling looks closer to a TV than repeating whole output pixels. (Milestone 10d
// replaces this with the NTSC filter running on the graphics card.)

uniform sampler2D picture;    // RGBA8 texture exactly the picture's size, linear filtering
uniform vec4 viewport;        // where the picture is drawn, in window pixels: x, y, width, height

out vec4 color;

void main() {
    vec2 position = (gl_FragCoord.xy - viewport.xy) / viewport.zw;
    position.y = 1.0 - position.y;
    color = vec4(texture(picture, position).rgb, 1.0);
}
