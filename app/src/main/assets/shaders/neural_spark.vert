#version 300 es
// neural_spark.vert: the nodes and the signals, one glow sprite each from a
// uniform array (x, y, radius, opacity; dp), coloured along the diagonal.
uniform vec2 uTargetPx;   // the target (px)
uniform float uDensity;   // px per dp
uniform vec4 uSparks[160];
#include neural_shade

out vec2 vLocal;          // -1..1 across the sprite
flat out vec3 vColor;
flat out float vAlpha;

void main() {
    vec4 s = uSparks[gl_InstanceID];
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) * 2.0 - 1.0;
    vLocal = corner;
    vColor = shade(s.xy);
    vAlpha = s.w;
    vec2 pos = (s.xy + corner * s.z) * uDensity;
    gl_Position = vec4(pos.x / uTargetPx.x * 2.0 - 1.0, 1.0 - pos.y / uTargetPx.y * 2.0, 0.0, 1.0);
}
