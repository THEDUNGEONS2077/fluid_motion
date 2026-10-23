#version 300 es
// velocity_flare.frag: a soft bloom (pale at its heart → the flare colour
// at 18% → faint at 50% → nothing, interpolated as canvas gradients are,
// unpremultiplied) and an arc of light through the corner, both breathing.
// Premultiplied, additive.
precision mediump float;

uniform vec3 uPale;
uniform vec3 uFlare;
uniform highp vec3 uBloom;     // centre x, y, radius (dp)
uniform highp vec3 uArc;       // centre x, y, radius (dp)
uniform vec2 uArcSpan;         // from, to (radians, clockwise from +x: screen y runs down)
uniform float uArcHalf;        // half the arc's thickness (dp)
uniform float uStrength;       // the GLOW level × the breathing
uniform float uArcAlpha;
uniform highp float uDensity;  // px per dp (as the vertex shader's: a shared uniform's precision must match)

in highp vec2 vP;
flat in int vPart;
out vec4 fragColor;

void main() {
    if (vPart == 0) {
        float r = length(vP - uBloom.xy) / uBloom.z;
        float g = uStrength;
        vec4 k = r < 0.18 ? mix(vec4(uPale, min(1.0, 0.95 * g)), vec4(uFlare, min(1.0, 0.6 * g)), r / 0.18)
            : r < 0.5 ? mix(vec4(uFlare, min(1.0, 0.6 * g)), vec4(uFlare, min(1.0, 0.16 * g)), (r - 0.18) / 0.32)
            : mix(vec4(uFlare, min(1.0, 0.16 * g)), vec4(uFlare, 0.0), clamp((r - 0.5) / 0.5, 0.0, 1.0));
        fragColor = vec4(k.rgb * k.a, k.a);
        return;
    }
    highp vec2 d = vP - uArc.xy;
    float off = abs(length(d) - uArc.z) * uDensity;
    float cover = clamp(uArcHalf * uDensity + 0.5 - off, 0.0, 1.0);
    float angle = atan(d.y, d.x);
    if (angle < uArcSpan.x || angle > uArcSpan.y) cover = 0.0;
    float a = cover * min(1.0, uArcAlpha * uStrength);
    fragColor = vec4(uFlare * a, a);
}
