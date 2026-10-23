#version 300 es
// neural_bg.frag: the neural net's light, as app.css draws it under the
// web's canvas: --cool tinting the background from the top, the --accent
// glowing up from the bottom right (softened at the user's request,
// 2026-10-23). CSS gradients mix in premultiplied colour: an accent stop
// at x% is the accent at alpha x%.
precision mediump float;

uniform vec3 uBg;
uniform vec3 uCool;
uniform vec3 uAccent;

in highp vec2 vPos;
out vec4 fragColor;

// Noise of ±half a step, so the dark gradients never band (interleaved gradient noise).
highp float noise(highp vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// A CSS ellipse's gradient position: 0 at its centre, 1 on its edge (size as shares of the screen).
float ellipse(vec2 at, vec2 size) {
    return length((vPos - at) / size);
}

void main() {
    // The background: cool 30% → 20% at 55% → 12% at the bottom, mixed into --bg.
    float y = vPos.y;
    float k = y < 0.55 ? mix(0.30, 0.20, y / 0.55) : mix(0.20, 0.12, (y - 0.55) / 0.45);
    vec3 c = mix(uBg, uCool, k);
    // The wide glow (85% × 58% at 55% 100%): accent 16% → 0 at 85%.
    float wide = 0.16 * max(0.0, 1.0 - ellipse(vec2(0.55, 1.0), vec2(0.85, 0.58)) / 0.85);
    c = mix(c, uAccent, wide);
    // The corner glow over it (58% × 62% at 84% 100%): 52% → 34% at 36% → 12% at 70% → 0.
    float t = ellipse(vec2(0.84, 1.0), vec2(0.58, 0.62));
    float corner = t < 0.36 ? mix(0.52, 0.34, t / 0.36)
        : t < 0.70 ? mix(0.34, 0.12, (t - 0.36) / 0.34)
        : mix(0.12, 0.0, clamp((t - 0.70) / 0.30, 0.0, 1.0));
    c = mix(c, uAccent, corner);
    fragColor = vec4(c + (noise(gl_FragCoord.xy) - 0.5) / 255.0, 1.0);
}
