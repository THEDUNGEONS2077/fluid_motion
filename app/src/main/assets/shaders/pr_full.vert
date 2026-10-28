#version 300 es
// prism full.vert: one triangle over the whole screen (no buffer: from
// gl_VertexID), for the background.
out vec2 vUv;

void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
