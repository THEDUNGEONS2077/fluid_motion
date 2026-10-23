#version 300 es
// flow_seg.frag: a trace's stretch: its core, smooth-edged; a packet's
// fading from its head back over its tail (as the web's slices, squared,
// but smooth); and its halo, flat (a packet's, as the web strokes it) or
// soft (the board's glow). Premultiplied: the board's drawn over, the
// light added.
precision mediump float;

in float vAcross;
in float vAlong;
flat in vec4 vColor;
flat in vec4 vWidth;
flat in float vTail;
out vec4 fragColor;

void main() {
    float d = abs(vAcross);
    float core = clamp(vWidth.x + 0.5 - d, 0.0, 1.0);
    if (vTail > 0.0) {
        float f = clamp(1.0 - vAlong / vTail, 0.0, 1.0);
        core *= f * f;
    }
    float halo = 0.0;
    if (vWidth.y > 0.0) {
        if (vWidth.w > 0.5) {
            float h = clamp(1.0 - d / vWidth.y, 0.0, 1.0);
            halo = vWidth.z * h * h;
        } else {
            halo = vWidth.z * clamp(vWidth.y + 0.5 - d, 0.0, 1.0);
        }
    }
    float a = vColor.a * (core + halo * (1.0 - core));
    fragColor = vec4(vColor.rgb * a, a);
}
