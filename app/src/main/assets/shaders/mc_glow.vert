#version 300 es
// megacity glow.vert: a light (a beacon, a drone and its tail) or a patch
// of haze (sim.ts GLOW), one quad round it.
// One set of shaders, byte for byte: cyberdeck_terminal src/wallpaper/megacity/shaders/glow.vert, fluid_motion assets/shaders/mc_glow.vert.
layout(location = 0) in vec4 aPos;    // x, y, halo (or patch) radius across, down (CSS px)
layout(location = 1) in vec4 aColor;  // r, g, b, strength
layout(location = 2) in vec4 aShape;  // core radius, tail (CSS px, + behind a light going right), kind (0 light, 1 haze), unused

uniform vec2 uTarget;
uniform float uDensity;

out vec2 vLocal;         // CSS px from the light's head (or the patch's middle)
flat out vec4 vPos;
flat out vec4 vColor;
flat out vec4 vShape;

void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    float tail = aShape.y;
    vec2 lo = vec2(-aPos.z - max(tail, 0.0), -aPos.w);
    vec2 hi = vec2(aPos.z + max(-tail, 0.0), aPos.w);
    vLocal = mix(lo, hi, corner);
    vPos = aPos;
    vColor = aColor;
    vShape = aShape;
    vec2 px = (aPos.xy + vLocal) * uDensity;
    gl_Position = vec4(px.x / uTarget.x * 2.0 - 1.0, 1.0 - px.y / uTarget.y * 2.0, 0.0, 1.0);
}
