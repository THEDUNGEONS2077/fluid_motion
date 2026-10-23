#version 300 es
// megacity shape.vert: the foreground's triangles (city.ts foreground: the
// railing and the ledge), each vertex with its own colour.
// One set of shaders, byte for byte: cyberdeck_terminal src/wallpaper/megacity/shaders/shape.vert, fluid_motion assets/shaders/mc_shape.vert.
layout(location = 0) in vec2 aPos;    // CSS px
layout(location = 1) in vec4 aColor;  // premultiplied

uniform vec2 uTarget;
uniform float uDensity;

out vec4 vColor;

void main() {
    vColor = aColor;
    vec2 px = aPos * uDensity;
    gl_Position = vec4(px.x / uTarget.x * 2.0 - 1.0, 1.0 - px.y / uTarget.y * 2.0, 0.0, 1.0);
}
