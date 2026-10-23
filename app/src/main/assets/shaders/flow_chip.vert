#version 300 es
// flow_chip.vert: a chip (lib/flow/FlowFrame.kt CHIP): one quad over its
// body, a couple of dp to spare for its edge.
layout(location = 0) in vec4 aRect;  // left, top, width, height (dp)
layout(location = 1) in vec2 aChip;  // main (1), its glow (0–1)

uniform vec2 uTargetPx;  // the target (px)
uniform float uDensity;  // px per dp

out highp vec2 vLocal;   // dp from the body's top left
flat out vec4 vChip;     // width, height, main, glow

void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vec2 pad = vec2(2.0);
    vLocal = -pad + corner * (aRect.zw + 2.0 * pad);
    vChip = vec4(aRect.zw, aChip);
    vec2 px = (aRect.xy + vLocal) * uDensity;
    gl_Position = vec4(px.x / uTargetPx.x * 2.0 - 1.0, 1.0 - px.y / uTargetPx.y * 2.0, 0.0, 1.0);
}
