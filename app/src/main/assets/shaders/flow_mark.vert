#version 300 es
// flow_mark.vert: a round or square mark (lib/flow/FlowFrame.kt MARK): a
// via, a pad, a packet's head, a flash; one quad round it, a pixel to spare.
layout(location = 0) in vec4 aMark;  // x, y, size (dp), kind
layout(location = 1) in vec2 aLook;  // cool (1) or warm, opacity

uniform vec2 uTargetPx;  // the target (px)
uniform float uDensity;  // px per dp

out vec2 vLocal;         // dp from the mark's centre
flat out vec4 vMark;     // size, kind, cool, opacity

void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) * 2.0 - 1.0;
    // A via's ring reaches past its radius by half its line.
    float reach = aMark.z + 1.5 / uDensity + 1.0;
    vLocal = corner * reach;
    vMark = vec4(aMark.z, aMark.w, aLook.x, aLook.y);
    vec2 px = (aMark.xy + vLocal) * uDensity;
    gl_Position = vec4(px.x / uTargetPx.x * 2.0 - 1.0, 1.0 - px.y / uTargetPx.y * 2.0, 0.0, 1.0);
}
