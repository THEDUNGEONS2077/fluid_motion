#version 300 es
// sunset sky.frag: everything above the horizon, per pixel, drawn once
// (it never changes: engine.ts): the night sky (purple left, blue right,
// darker up top), fine stars, the striped sun with its glow, and the
// scanlines over it all.
precision highp float;
// Integers at full width too: the stars' hash multiplies 32-bit numbers (a phone's fragment shaders default to medium precision ints).
precision highp int;

uniform vec2 uTarget;   // the target (px)
uniform float uUnit;    // px per CSS px of the reference layout
uniform float uHorizon; // the horizon (px from the top)
uniform vec2 uSun;      // the sun's centre (px from the top)
uniform float uSunR;    // its radius (px)
uniform float uGlowR;   // its glow's reach (px)
uniform vec3 uSkyLeft;
uniform vec3 uSkyRight;
uniform vec3 uSkyTop;
uniform vec3 uSunTop;
uniform vec3 uSunBase;
uniform vec3 uGlow;
uniform vec3 uStar;
uniform vec3 uStripe;   // from, period, the first gap's width (sun units)
uniform float uGapGrow; // how much wider the gaps get by the sun's base
uniform float uStarCell;   // a star cell (CSS px)
uniform float uStarChance; // the share of cells with a star
uniform float uScanEvery;  // a dark row every so many CSS px
uniform float uScanDepth;  // how dark it is

out vec4 fragColor;

// A hash of a cell: 0 ≤ n < 1 (integer mixing: no streaks, unlike a sine hash).
float hash(vec2 p) {
    uvec2 q = uvec2(ivec2(p) + 32768) * uvec2(1597334673u, 3812015801u);
    uint n = (q.x ^ q.y) * 1597334673u;
    n ^= n >> 16;
    return float(n) * (1.0 / 4294967296.0);
}

void main() {
    vec2 p = vec2(gl_FragCoord.x, uTarget.y - gl_FragCoord.y);
    vec2 s = p / uTarget;
    // The sky: purple to blue across, darker toward the top, a little lighter near the horizon.
    vec3 c = mix(uSkyLeft, uSkyRight, smoothstep(0.1, 0.95, s.x));
    c = mix(uSkyTop, c, smoothstep(0.0, uHorizon / uTarget.y, s.y) * 0.85 + 0.15);
    // Stars: a few cells lit, of varied brightness; a rare bright one.
    vec2 cell = floor(p / (uStarCell * uUnit));
    float h = hash(cell);
    if (h < uStarChance) {
        vec2 q = fract(p / (uStarCell * uUnit)) - 0.5;
        float b = 1.0 - smoothstep(0.1, 0.5, length(q));
        c += uStar * b * (0.35 + 0.65 * hash(cell + 7.0)) * (h < uStarChance * 0.08 ? 2.0 : 1.0);
    }
    // The sun's glow, all round it.
    float d = length(p - uSun);
    c += uGlow * exp(-max(d - uSunR, 0.0) / uGlowR) * 0.55;
    // The sun: golden to pink down its face, cut by gaps that widen toward its base.
    if (d < uSunR + 1.0) {
        float v = (p.y - uSun.y) / uSunR;
        vec3 sun = mix(uSunTop, uSunBase, smoothstep(-0.7, 0.9, v));
        float k = (v - uStripe.x) / uStripe.y;
        float gap = uStripe.z + uGapGrow * clamp((v - uStripe.x) / (1.0 - uStripe.x), 0.0, 1.0);
        // In a gap: below where they start, in the band's top `gap` share (its edges anti-aliased over a pixel).
        float pxPerBand = uStripe.y * uSunR;
        float edge = 1.0 / pxPerBand;
        float inGap = v > uStripe.x ? 1.0 - smoothstep(gap - edge, gap + edge, fract(k)) : 0.0;
        float inside = 1.0 - smoothstep(uSunR - 0.75, uSunR + 0.75, d);
        c = mix(c, sun, inside * (1.0 - inGap));
    }
    // Scanlines.
    c *= 1.0 - uScanDepth * step(uScanEvery - 1.0, mod(p.y / uUnit, uScanEvery));
    fragColor = vec4(c, 1.0);
}
