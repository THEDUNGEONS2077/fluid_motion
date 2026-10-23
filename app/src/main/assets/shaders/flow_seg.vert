#version 300 es
// flow_seg.vert: a straight stretch of trace (lib/flow/FlowFrame.kt SEG):
// one quad round it, as wide as its core (plus a pixel) or its halo,
// capped past its ends if asked (to fill a bend's corner).
layout(location = 0) in vec4 aEnds;   // x0, y0, x1, y1 (dp)
layout(location = 1) in vec4 aColor;  // r, g, b, opacity
layout(location = 2) in vec4 aWidth;  // half core, half halo (dp), halo strength, soft (1) or flat (0)
layout(location = 3) in vec4 aFade;   // from the head at each end (dp), the tail (dp; 0: no fade), cap (dp)

uniform vec2 uTargetPx;  // the target (px)
uniform float uDensity;  // px per dp

out float vAcross;       // px from the centre line
out float vAlong;        // dp from a packet's head
flat out vec4 vColor;
flat out vec4 vWidth;    // half core and half halo in px, halo strength, soft
flat out float vTail;

void main() {
    vec2 a = aEnds.xy;
    vec2 b = aEnds.zw;
    vec2 d = b - a;
    float len = length(d);
    vec2 u = len > 0.0 ? d / len : vec2(1.0, 0.0);
    vec2 n = vec2(-u.y, u.x);
    float halfWidth = max(aWidth.x + 1.0 / uDensity, aWidth.y);
    float side = float(gl_VertexID & 1) * 2.0 - 1.0;
    bool atB = (gl_VertexID >> 1) == 1;
    vec2 end = atB ? b + u * aFade.w : a - u * aFade.w;
    vec2 pos = end + n * side * halfWidth;
    vAcross = side * halfWidth * uDensity;
    vAlong = atB ? aFade.y : aFade.x;
    vColor = aColor;
    vWidth = vec4(aWidth.x * uDensity, aWidth.y * uDensity, aWidth.z, aWidth.w);
    vTail = aFade.z;
    vec2 px = pos * uDensity;
    gl_Position = vec4(px.x / uTargetPx.x * 2.0 - 1.0, 1.0 - px.y / uTargetPx.y * 2.0, 0.0, 1.0);
}
