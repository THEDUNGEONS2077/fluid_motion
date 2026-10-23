#version 300 es
// rain_ops.frag: colours a glyph from the atlas's four channels (R core,
// G head glow, B body glow, A erase shape: scene/rain/GlyphAtlas.kt) the way
// the web's canvas drew it, premultiplied. Drawn with
// glBlendFunc(ONE, ONE_MINUS_SRC_ALPHA), so a glyph is "source-over" and
// a fade or erase (black, with alpha) is "destination-out": one blend for
// the whole frame, one draw call.
precision mediump float;

uniform sampler2D uGlyphs;
uniform vec3 uBright;     // a head's core (--text-bright)
uniform vec3 uBody;       // a body's core (--text, muted)
uniform vec3 uDim;        // a dim body's core (--text-dim, muted)
uniform vec3 uGlowColor;  // the glow (--accent)
uniform float uGlowAlpha; // the glow colour's strength (GLOW level)
uniform float uHeadLayers;// how many times a head is drawn over itself (GLOW level)

in highp vec2 vUv;
flat in int vStyle;
flat in float vAlpha;
out vec4 fragColor;

const int HEAD = 0;
const int BODY = 1;
const int HALO = 3;
const int ERASE = 4;
const int FADE = 5;

void main() {
    if (vStyle == FADE) {
        fragColor = vec4(0.0, 0.0, 0.0, vAlpha);
        return;
    }
    vec4 t = texture(uGlyphs, vUv);
    vec4 c;
    if (vStyle == ERASE) {
        c = vec4(0.0, 0.0, 0.0, t.a);
    } else if (vStyle == HEAD || vStyle == HALO) {
        // One canvas draw: the glyph over its shadow. Drawn uHeadLayers times,
        // each over the last: x over x over … is this, in closed form.
        float s = uGlowAlpha * t.g;
        vec4 x = vec4(uBright * t.r, t.r) + vec4(uGlowColor * s, s) * (1.0 - t.r);
        c = x * ((1.0 - pow(1.0 - x.a, uHeadLayers)) / max(x.a, 1e-4));
        // A halo: what a head leaves behind, its glyph's shape cut out.
        if (vStyle == HALO) c *= 1.0 - t.a;
    } else {
        vec3 core = vStyle == BODY ? uBody : uDim;
        float s = uGlowAlpha * t.b;
        c = vec4(core * t.r, t.r) + vec4(uGlowColor * s, s) * (1.0 - t.r);
    }
    fragColor = c * vAlpha;
}
