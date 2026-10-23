#version 300 es
// velocity_bg.frag: VELOCITY's dark (near-black, a little cool light on the
// left, a hint of the accent at the top right: app.css), slid in *under*
// the light already drawn: blended with (ONE_MINUS_DST_ALPHA, ONE), so the
// result is light + background · (1 − light's alpha), as the web's
// additive canvas over its CSS background.
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

void main() {
    // Cool light (70% × 90% at 15% 45%): cool at 18% into the background, out to nothing.
    float t2 = length((vPos - vec2(0.15, 0.45)) / vec2(0.7, 0.9));
    vec3 c = mix(mix(uBg, uCool, 0.18), uBg, clamp(t2, 0.0, 1.0));
    // The accent's hint over it (55% × 40% at 80% 0%): 9% out to nothing.
    float t1 = length((vPos - vec2(0.8, 0.0)) / vec2(0.55, 0.4));
    c = mix(c, uAccent, 0.09 * max(0.0, 1.0 - t1));
    fragColor = vec4(c + (noise(gl_FragCoord.xy) - 0.5) / 255.0, 1.0);
}
