#version 300 es
// flow_mark.frag: the marks, as the web draws them: a via (a ring round a
// hole that shows the board through), a pad (a square, half filled), a
// packet's head (a glow, white-hot at the middle), a flash (a ring of
// light). Premultiplied: vias and pads drawn over, heads and flashes added.
precision mediump float;

uniform vec3 uBg;
uniform vec3 uWarmVia;
uniform vec3 uCoolVia;
uniform vec3 uWarmLight;
uniform vec3 uCoolLight;
uniform vec3 uWarmHead;
uniform vec3 uCoolHead;
uniform highp float uDensity;

in vec2 vLocal;
flat in vec4 vMark;
out vec4 fragColor;

const int VIA = 0;
const int PAD = 1;
const int HEAD = 2;

// A gradient's colour and opacity at t between stops (unpremultiplied, as canvas gradients mix).
vec4 stop(float t, float t0, vec4 a, float t1, vec4 b) {
    return mix(a, b, clamp((t - t0) / (t1 - t0), 0.0, 1.0));
}

void main() {
    bool cool = vMark.z > 0.5;
    int kind = int(vMark.y + 0.5);
    float size = vMark.x;
    float r = length(vLocal);
    vec4 c;
    if (kind == VIA) {
        vec3 via = cool ? uCoolVia : uWarmVia;
        // A 1.2 dp ring at the radius, the board's background inside it.
        float ring = clamp(0.6 * uDensity + 0.5 - abs(r - size) * uDensity, 0.0, 1.0);
        float inside = clamp((size - r) * uDensity + 0.5, 0.0, 1.0);
        float a = max(inside, ring);
        vec3 col = mix(uBg, via, ring / max(a, 1e-4));
        c = vec4(col * a, a);
    } else if (kind == PAD) {
        vec3 via = cool ? uCoolVia : uWarmVia;
        float box = max(abs(vLocal.x), abs(vLocal.y));
        float edge = clamp(0.5 * uDensity + 0.5 - abs(box - size) * uDensity, 0.0, 1.0);
        float fill = 0.45 * clamp((size - box) * uDensity + 0.5, 0.0, 1.0);
        float a = edge + fill * (1.0 - edge);
        c = vec4(via * a, a);
    } else {
        vec3 light = cool ? uCoolLight : uWarmLight;
        vec3 head = cool ? uCoolHead : uWarmHead;
        float t = r / size;
        vec4 k;
        if (kind == HEAD) {
            // White-hot at the middle, the packet's colour, then nothing.
            k = t < 0.2 ? stop(t, 0.0, vec4(head, 1.0), 0.2, vec4(mix(head, light, 0.5), 0.85))
                : t < 0.45 ? stop(t, 0.2, vec4(mix(head, light, 0.5), 0.85), 0.45, vec4(light, 0.35))
                : stop(t, 0.45, vec4(light, 0.35), 1.0, vec4(light, 0.0));
        } else {
            // A flash: a bright ring, soft both sides.
            k = t < 0.35 ? stop(t, 0.0, vec4(light, 0.0), 0.35, vec4(light, 0.35))
                : t < 0.5 ? stop(t, 0.35, vec4(light, 0.35), 0.5, vec4(head, 0.9))
                : t < 0.7 ? stop(t, 0.5, vec4(head, 0.9), 0.7, vec4(light, 0.3))
                : stop(t, 0.7, vec4(light, 0.3), 1.0, vec4(light, 0.0));
        }
        float a = k.a * vMark.w;
        c = vec4(k.rgb * a, a);
    }
    fragColor = c;
}
