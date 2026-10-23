#version 300 es
// neural_bokeh.vert: the out-of-focus particles behind the net, one quad
// each from a uniform array (x, y, radius, opacity; dp): nothing sent per
// frame but the array.
uniform vec2 uTargetPx;   // the target (px)
uniform float uDensity;   // px per dp
uniform vec4 uBokeh[32];
#include neural_shade

out vec2 vLocal;          // -1..1 across the disc
flat out vec3 vColor;
flat out float vAlpha;

void main() {
    vec4 b = uBokeh[gl_InstanceID];
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) * 2.0 - 1.0;
    vLocal = corner;
    float t = diagonal(b.xy);
    vColor = shade(b.xy);
    // Brighter down in the glow, where the reference's particles crowd.
    vAlpha = min(1.0, b.w * (0.6 + 0.9 * t));
    vec2 pos = (b.xy + corner * b.z) * uDensity;
    gl_Position = vec4(pos.x / uTargetPx.x * 2.0 - 1.0, 1.0 - pos.y / uTargetPx.y * 2.0, 0.0, 1.0);
}
