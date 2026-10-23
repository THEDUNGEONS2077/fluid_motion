#version 300 es
// velocity_panel.frag: a panel's light, the web's fills summed (they were
// all 'lighter'): soft at both edges; the pale one's faint spill round it;
// a red one strongest at the top; fine motion-blur lines through it (white,
// from the striation tile: one random brightness per dp row, as the web's
// pattern). Premultiplied, drawn additively.
precision mediump float;

uniform vec3 uPale;
uniform vec3 uRed;
uniform float uSoft;        // edge softness, share of the width (the pale one's is less)
uniform float uStriation;   // the lines' strength
uniform sampler2D uTile;    // 1 × 96: each dp row's line brightness
uniform highp float uScreenH;

in highp float vU;
in highp float vY;
flat in highp vec4 vPanel;
flat in int vPale;
out vec4 fragColor;

// A ramp's value at t between stops (t0, v0) and (t1, v1), held flat outside.
float ramp(float t, float t0, float v0, float t1, float v1) {
    return mix(v0, v1, clamp((t - t0) / (t1 - t0), 0.0, 1.0));
}

void main() {
    // Positions in highp (mediump is 16-bit on phones: a slowly drifting panel would move in steps).
    highp float x = vPanel.x;
    highp float w = vPanel.y;
    float a = vPanel.w;
    bool pale = vPale == 1;
    float soft = pale ? uSoft * 0.45 : uSoft;
    float light = 0.0;
    // The spill: 0 → 0.12a at 35% → 0.12a at 65% → 0, over 2.2 widths.
    if (pale) {
        float t = (vU - (x - 0.6 * w)) / (2.2 * w);
        light += t < 0.35 ? ramp(t, 0.0, 0.0, 0.35, 0.12 * a) : ramp(t, 0.65, 0.12 * a, 1.0, 0.0);
    }
    // The body: 0 → a at the soft edge → 0.92a → 0.
    float t = (vU - x) / w;
    if (t >= 0.0 && t <= 1.0) {
        light += t < soft ? ramp(t, 0.0, 0.0, soft, a)
            : t < 1.0 - soft ? ramp(t, soft, a, 1.0 - soft, 0.92 * a)
            : ramp(t, 1.0 - soft, 0.92 * a, 1.0, 0.0);
    }
    // A red panel's top: 0.55a fading out by 45% of the height, inside its soft edges.
    if (!pale && t >= soft && t <= 1.0 - soft) light += ramp(vY / uScreenH, 0.0, 0.55 * a, 0.45, 0.0);
    // The fine lines (white light), inside the panel less half its soft edge each side.
    float stri = 0.0;
    if (t >= 0.09 && t <= 0.91) stri = uStriation * a * (pale ? 0.22 : 1.0) * texture(uTile, vec2(0.5, vY / 96.0)).r;
    vec3 c = pale ? uPale : uRed;
    fragColor = vec4(c * light + vec3(stri), light + stri);
}
