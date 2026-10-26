#version 300 es
// sunset full.vert: one triangle covering the whole target; the sky and the
// ground are worked out per pixel from gl_FragCoord (sky.frag, ground.frag).
void main() {
    vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
    gl_Position = vec4(p, 0.0, 1.0);
}
