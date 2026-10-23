#version 300 es
// megacity sign.vert: a neon sign or billboard (sim.ts SIGN), one quad over
// it and its halo.
// One set of shaders, byte for byte: cyberdeck_terminal src/wallpaper/megacity/shaders/sign.vert, fluid_motion assets/shaders/mc_sign.vert.
layout(location = 0) in vec4 aRect;   // left, top, width, height (CSS px)
layout(location = 1) in vec4 aColor;  // r, g, b, brightness
layout(location = 2) in vec4 aSign;   // seed, upright (1) or wide, halo (CSS px), unused

uniform vec2 uTarget;    // the target (px)
uniform float uDensity;  // px per CSS px

out vec2 vLocal;         // CSS px from the sign's top left
flat out vec4 vRect;
flat out vec4 vColor;
flat out vec4 vSign;

void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    float m = aSign.z + 1.0;
    vec2 pos = aRect.xy - m + corner * (aRect.zw + 2.0 * m);
    vLocal = pos - aRect.xy;
    vRect = aRect;
    vColor = aColor;
    vSign = aSign;
    vec2 px = pos * uDensity;
    gl_Position = vec4(px.x / uTarget.x * 2.0 - 1.0, 1.0 - px.y / uTarget.y * 2.0, 0.0, 1.0);
}
