#version 300 es
// velocity_light.frag: the long lines (red: 0 → a at 45% → 0.4a across the
// screen) and the streaks (a bright head trailing a fading tail, with a
// faint glow 4 thicknesses tall round it), as the web's fills. Edges are
// smoothed over a pixel, as canvas fills are. Premultiplied, additive.
precision mediump float;

uniform vec3 uPale;
uniform vec3 uRed;
uniform float uGlow;    // the GLOW level's strength
uniform highp float uDensity; // px per dp (as the vertex shader's: a shared uniform's precision must match)

in highp vec2 vP;
flat in highp vec4 vShape;
flat in vec2 vLook;
out vec4 fragColor;

void main() {
    if (vLook.y < 0.0) {
        // A long line.
        float t = vP.x / vShape.z;
        float a = vLook.x * (t < 0.45 ? t / 0.45 : mix(1.0, 0.4, (t - 0.45) / 0.55));
        fragColor = vec4(uRed * a, a);
        return;
    }
    // A streak: along its tail 0 → full at the head; its core is one thickness, its glow four.
    float along = clamp((vP.x - (vShape.x - vShape.z)) / vShape.z, 0.0, 1.0);
    float head = clamp((vShape.x - vP.x) * uDensity + 0.5, 0.0, 1.0);
    float dy = abs(vP.y - vShape.y) * uDensity;
    float core = clamp(vShape.w * 0.5 * uDensity + 0.5 - dy, 0.0, 1.0);
    float halo = clamp(vShape.w * 2.0 * uDensity + 0.5 - dy, 0.0, 1.0);
    float a = along * head * min(1.0, vLook.x * uGlow) * (core + 0.18 * uGlow * halo);
    vec3 c = vLook.y > 0.5 ? uPale : uRed;
    fragColor = vec4(c * a, a);
}
