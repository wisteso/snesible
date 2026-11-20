#version 330 core
// Covers the viewport with one triangle, without any vertex data.
//
// Vertex 0 lands at (-1,-1), vertex 1 at (3,-1) and vertex 2 at (-1,3): a triangle twice the
// size of the screen whose visible part is exactly the [-1,1] square. Drawing one triangle
// instead of two avoids a seam along the diagonal. The fragment shaders work out where each
// pixel is from gl_FragCoord, so no texture coordinates are passed along.
void main() {
    vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2); // (0,0), (2,0), (0,2)
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}
