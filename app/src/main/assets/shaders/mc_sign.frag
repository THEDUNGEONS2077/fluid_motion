#version 300 es
// megacity sign.frag: a sign's panel in its colour with glyphs on it (a
// column of square cells down an upright sign, a row along a wide one, each
// a 3 × 4 grid of strokes picked by a hash), a brighter rim, and a halo
// round it. Premultiplied: the panel covers the tower, the halo (no alpha)
// is added; a sign flickering off is a dark panel.
// One set of shaders, byte for byte: cyberdeck_terminal src/wallpaper/megacity/shaders/sign.frag, fluid_motion assets/shaders/mc_sign.frag.
precision highp float;
precision highp int;

uniform float uDensity;

in vec2 vLocal;
flat in vec4 vRect;
flat in vec4 vColor;
flat in vec4 vSign;
out vec4 fragColor;

uint hash(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352dU;
    x ^= x >> 15;
    x *= 0x846ca68bU;
    x ^= x >> 16;
    return x;
}

void main() {
    vec2 size = vRect.zw;
    vec2 q = vLocal;
    vec2 d = abs(q - size * 0.5) - size * 0.5;
    float outside = max(d.x, d.y);
    float inside = clamp(0.5 - outside * uDensity, 0.0, 1.0);
    float bright = vColor.a;
    vec3 panel = vColor.rgb;
    // Glyphs: cells as wide as the sign is (upright) or as tall (wide), strokes in a 3 × 4 grid.
    bool upright = vSign.y > 0.5;
    float cs = upright ? size.x : size.y;
    vec2 along = upright ? q : q.yx;
    float k = floor(along.y / cs);
    vec2 f = vec2(along.x, along.y - k * cs) / cs;
    vec2 inner = (f - 0.18) / 0.64;
    float stroke = 0.0;
    if (all(greaterThanEqual(inner, vec2(0.0))) && all(lessThan(inner, vec2(1.0)))) {
        uvec2 sub = uvec2(floor(inner * vec2(3.0, 4.0)));
        stroke = float(hash(uint(vSign.x) ^ hash(uint(k) * 31u + sub.x * 7u + sub.y)) % 100u < 45u);
    }
    vec3 c = panel * (0.62 + 0.3 * stroke) + vec3(0.42) * stroke;
    // A brighter rim along the panel's edge.
    float rim = clamp(1.0 - abs(outside + 0.6) * uDensity, 0.0, 1.0);
    c = mix(c, min(panel * 1.5 + 0.15, vec3(1.0)), rim * 0.7);
    float halo = exp(-max(outside, 0.0) / max(vSign.z * 0.4, 0.5)) * (1.0 - inside) * 0.7;
    fragColor = vec4(c * inside * bright + panel * halo * bright, inside);
}
