#version 300 es
// prism back.frag: what's behind the glass: deep navy, lighter towards the
// bottom (the reference is), with wide soft pools of the accent and the
// cool colour where the light comes through.
precision highp float;

uniform vec3 uBg;      // the deep navy
uniform vec3 uAccent;  // magenta
uniform vec3 uCool;    // electric blue
uniform vec2 uAspect;  // (w / h, 1): pools stay round
uniform float uLight;  // the glow level's light strength

in vec2 vUv;
out vec4 fragColor;

// A soft pool of light: its centre (shares of the screen) and size.
float pool(vec2 c, float r) {
    vec2 d = (vUv - c) * uAspect;
    return exp(-dot(d, d) / (r * r));
}

void main() {
    // vUv.y is 0 at the bottom: brighter there.
    vec3 c = uBg * mix(1.2, 0.55, vUv.y);
    c += uAccent * 0.09 * uLight * (pool(vec2(0.12, 0.62), 0.32) + pool(vec2(0.95, 0.55), 0.3) + 0.7 * pool(vec2(0.42, 0.18), 0.28));
    c += uCool * 0.08 * uLight * (pool(vec2(0.55, 0.62), 0.35) + pool(vec2(0.3, 0.3), 0.3) + pool(vec2(0.8, 0.12), 0.25));
    fragColor = vec4(c, 1.0);
}
