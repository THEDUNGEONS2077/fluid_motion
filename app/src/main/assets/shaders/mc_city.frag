#version 300 es
// megacity city.frag: the whole city, once per pixel. Each layer of towers
// is a skyline (city.ts skyline: per column the top there, the tower's left
// edge, width and seed); the pixel walks the layers near to far and takes
// the first tower it's inside, so nothing is drawn twice. A tower's face,
// its lit roof edge, a bright strip down one side for some, and its windows
// are all made here from its seed: a grid of small dashes, each lit or dark
// by a hash, and each re-lit or put out at its own moments as the clock runs
// (WINDOW_PERIOD on average), so a few change every second. Then the haze:
// far layers take more of its colour. Where no tower is, the haze itself.
// One set of shaders, byte for byte: cyberdeck_terminal src/wallpaper/megacity/shaders/city.frag, fluid_motion assets/shaders/mc_city.frag.
precision highp float;
precision highp int;
precision highp sampler2D;

uniform sampler2D uSkyline;
uniform int uLayers;
uniform int uRows;        // skyline rows per layer
uniform int uCols;        // columns across
uniform vec2 uTarget;     // the target (px)
uniform float uDensity;   // px per CSS px (dp on a phone)
uniform float uHeight;    // the screen's height (CSS px)
uniform float uTime;      // the city's clock (s, wrapped)
uniform vec2 uCell[5];    // each layer's window cell (CSS px)
uniform float uFog[5];    // each layer's share of the haze
uniform vec3 uSkyTop;
uniform vec3 uSkyMid;
uniform vec3 uSkyLow;
uniform vec3 uLowGlow;
uniform vec3 uFaceFar;
uniform vec3 uFaceNear;
uniform vec3 uEdge;
uniform vec3 uWinPale;
uniform vec3 uWinViolet;
uniform vec3 uWinPink;
uniform vec3 uWinWhite;
uniform float uWindowGlow;

out vec4 fragColor;

const int SKYLINE_WIDTH = 2048;
const float WINDOW_PERIOD = 4000.0;

uint hash(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352dU;
    x ^= x >> 15;
    x *= 0x846ca68bU;
    x ^= x >> 16;
    return x;
}

// A hash as 0–1.
float rnd(uint x) {
    return float(hash(x) >> 8) * (1.0 / 16777216.0);
}

// The haze at height y (CSS px): --cool at the top, violet low down, a glow rising from below (city.ts hazeAt).
vec3 haze(float y) {
    float t = clamp(y / uHeight, 0.0, 1.0);
    vec3 c = t < 0.5 ? mix(uSkyTop, uSkyMid, t * 2.0) : mix(uSkyMid, uSkyLow, t * 2.0 - 1.0);
    return c + uLowGlow * smoothstep(0.4, 1.0, t);
}

vec3 windowColour(uint id) {
    float k = rnd(id + 5u);
    return k < 0.55 ? uWinPale : k < 0.76 ? uWinViolet : k < 0.85 ? uWinPink : uWinWhite;
}

// Tower t (top, left, width, seed) of layer L at p (CSS px).
vec3 tower(int L, vec2 p, vec4 t) {
    float depth = float(L) / float(uLayers - 1);
    bool spire = t.a < 0.0;
    uint seed = uint(abs(t.a));
    vec3 c = mix(uFaceFar, uFaceNear, depth) * (0.8 + 0.4 * rnd(seed * 3u + 1u));
    float lx = p.x - t.g;
    float onePx = 1.0 / uDensity;
    // The roof's edge catches the light.
    if (p.y - t.r < max(onePx, 0.75)) c = mix(c, uEdge, 0.4);
    if (!spire && t.b > 6.0) {
        // A bright strip down one edge, on some towers.
        uint side = hash(seed + 11u) % 6u;
        if ((side == 0u && lx < 1.0) || (side == 1u && t.b - lx < 1.0)) c = mix(c, uEdge, 0.65);
        // Windows: a grid from just inside the left edge and under the roof.
        vec2 cell = uCell[L] * (0.85 + 0.4 * vec2(rnd(seed + 21u), rnd(seed + 22u)));
        vec2 g = vec2(lx - 1.5, p.y - t.r - 3.0) / cell;
        if (g.x >= 0.0 && g.y >= 0.0 && lx < t.b - 1.5) {
            vec2 ci = floor(g);
            vec2 f = (g - ci) * cell;
            uint id = hash(seed ^ hash(uint(ci.x) * 73856093u ^ uint(ci.y) * 19349663u));
            // How lit: some towers nearly dark, most part lit; whole floors lit or dark together, a few
            // blazing; far fewer far off (the haze swallows them).
            float lit = rnd(seed + 31u) < 0.2 ? 0.03 : 0.12 + 0.3 * rnd(seed + 32u);
            float floorBand = rnd(seed ^ hash(uint(ci.y / 3.0) + 101u));
            lit *= (floorBand < 0.35 ? 0.2 : floorBand < 0.88 ? 1.0 : 2.4) * mix(0.45, 1.0, depth);
            float epoch = floor((uTime + rnd(id + 2u) * WINDOW_PERIOD) / WINDOW_PERIOD);
            bool on = rnd(id ^ hash(uint(epoch) + 77u)) < lit;
            // The window: a dash across the cell, its edges smooth to the pixel.
            vec2 dash = vec2(cell.x - 1.0, max(onePx, cell.y * 0.3));
            vec2 d = abs(f - cell * 0.5) - dash * 0.5;
            float inside = clamp(0.5 - max(d.x, d.y) * uDensity, 0.0, 1.0);
            if (on) {
                vec3 w = windowColour(id) * (0.7 + 0.5 * rnd(id + 9u));
                float halo = uWindowGlow * exp(-length(max(d, 0.0)) * 1.6) * (1.0 - inside);
                c = mix(c, w, inside) + w * halo * 0.35;
            } else {
                c *= 1.0 - 0.18 * inside;
            }
        }
    }
    return mix(c, haze(p.y), uFog[L]);
}

void main() {
    vec2 p = vec2(gl_FragCoord.x, uTarget.y - gl_FragCoord.y) / uDensity;
    int col = clamp(int(p.x), 0, uCols - 1);
    vec3 c = haze(p.y);
    for (int L = uLayers - 1; L >= 0; L--) {
        vec4 t = texelFetch(uSkyline, ivec2(col % SKYLINE_WIDTH, L * uRows + col / SKYLINE_WIDTH), 0);
        if (p.y >= t.r) {
            c = tower(L, p, t);
            break;
        }
    }
    // Noise of ±half a step, so the haze's long gradients never band.
    float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    fragColor = vec4(c + (n - 0.5) / 255.0, 1.0);
}
