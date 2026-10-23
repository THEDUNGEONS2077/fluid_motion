#version 300 es
// fullscreen.vert: one triangle covering the whole target, built from
// gl_VertexID alone (no buffers). vPos is the place on the target as shares
// of it: (0, 0) at the top left, (1, 1) at the bottom right.
out vec2 vPos;

void main() {
    vec2 share = vec2(gl_VertexID == 1 ? 2.0 : 0.0, gl_VertexID == 2 ? 2.0 : 0.0);
    vPos = share;
    gl_Position = vec4(share.x * 2.0 - 1.0, 1.0 - share.y * 2.0, 0.0, 1.0);
}
