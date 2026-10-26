#version 300 es
// horizon full.vert: one triangle covering the whole target; the sky is
// worked out per pixel from gl_FragCoord (sky.frag).
// One set of shaders: cyberdeck_terminal src/wallpaper/horizon/shaders/*, and later fluid_motion's own copy, byte for byte.
void main() {
    vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
    gl_Position = vec4(p, 0.0, 1.0);
}
