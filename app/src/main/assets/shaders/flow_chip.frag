#version 300 es
// flow_chip.frag: a chip as the web draws it. On the board (uPass 0): its
// dark body, a 1.2 dp edge, a pin-1 mark, and on the main chip its die (an
// inner square two points in, with a fine grid). Taking data in (uPass 1,
// added): a wash of the accent over the body, its edge lit.
precision mediump float;

uniform vec3 uBody;
uniform vec3 uEdge;
uniform vec3 uDie;
uniform vec3 uLight;
uniform int uPass;
uniform highp float uDensity;
uniform highp float uPitch;

in highp vec2 vLocal;
flat in vec4 vChip;
out vec4 fragColor;

// How much of a line [halfWidth] dp either side of where [d] (dp from it) is 0 covers this pixel.
float line(highp float d, float halfWidth) {
    return clamp(halfWidth * uDensity + 0.5 - abs(d) * uDensity, 0.0, 1.0);
}

void main() {
    highp vec2 p = vLocal;
    highp float w = vChip.x;
    highp float h = vChip.y;
    // Inside the body (smooth-edged), and how far from its edge (dp: inside positive).
    highp float inner = min(min(p.x, w - p.x), min(p.y, h - p.y));
    float inside = clamp(inner * uDensity + 0.5, 0.0, 1.0);
    float edge = line(inner, uPass == 1 ? 0.75 : 0.6);
    if (uPass == 1) {
        float g = vChip.w;
        float a = 0.1 * g * inside + 0.55 * g * edge;
        fragColor = vec4(uLight * a, a);
        return;
    }
    vec3 col = mix(uBody, uEdge, edge);
    // The pin-1 mark: a small dot near the top-left corner.
    float dotR = max(1.5, uPitch * 0.14);
    float pin1 = clamp((dotR - length(p - vec2(uPitch * 0.7))) * uDensity + 0.5, 0.0, 1.0) * 0.8;
    col = mix(col, uEdge, pin1);
    if (vChip.z > 0.5) {
        // The die: an inner square two points in, its edge, and a fine grid on it.
        highp float inset = uPitch * 2.0;
        highp vec2 q = p - vec2(inset);
        highp vec2 size = vec2(w, h) - 2.0 * inset;
        highp float dieIn = min(min(q.x, size.x - q.x), min(q.y, size.y - q.y));
        if (dieIn > -1.0) {
            float dieEdge = line(dieIn, 0.5) * 0.9;
            highp vec2 cell = mod(q, uPitch);
            highp vec2 g2 = min(cell, uPitch - cell);
            float grid = dieIn > 0.5 ? max(line(g2.x, 0.5), line(g2.y, 0.5)) * 0.35 : 0.0;
            col = mix(col, uDie, max(dieEdge, grid));
        }
    }
    float a = max(inside, edge);
    fragColor = vec4(col * a, a);
}
