#version 300 es
// neural_link.vert: one quad per link, its ends looked up in the nodes'
// positions (a uniform array, two nodes a vec4): the links themselves
// (which nodes, how bright) are sent once per screen. Opacity by length
// (longer is fainter) times the link's own weight, as the web's strokes;
// links wholly off one side of the screen are dropped.
layout(location = 0) in vec3 aLink;  // node a, node b, weight

uniform vec2 uTargetPx;   // the target (px)
uniform float uDensity;   // px per dp
uniform vec4 uNodes[64];  // node positions (dp): node i at [i / 2].xy or .zw
uniform float uMaxLink;   // the longest link (dp)
uniform float uWidth;     // the stroke's width (dp)
uniform float uStrength;  // the pass's opacity (the glow pass is faint)
#include neural_shade

out float vAcross;        // px from the centre line
flat out vec3 vColor;
flat out float vAlpha;
flat out float vHalf;     // half the stroke's width (px)

vec2 node(int i) {
    vec4 v = uNodes[i >> 1];
    return (i & 1) == 0 ? v.xy : v.zw;
}

void main() {
    vec2 a = node(int(aLink.x + 0.5));
    vec2 b = node(int(aLink.y + 0.5));
    // Wholly off the screen: no quad at all (outside the clip volume).
    if ((a.x < 0.0 && b.x < 0.0) || (a.x > uScreen.x && b.x > uScreen.x) || (a.y < 0.0 && b.y < 0.0) || (a.y > uScreen.y && b.y > uScreen.y)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        vAcross = 0.0; vColor = vec3(0.0); vAlpha = 0.0; vHalf = 0.0;
        return;
    }
    float len = length(b - a);
    float share = min(1.0, len / uMaxLink);
    vAlpha = uStrength * min(1.0, mix(0.6, 0.16, share) * aLink.z);
    vColor = shade((a + b) * 0.5);
    // The quad: the segment, widened by half the stroke plus a pixel for the smooth edge.
    vec2 along = len > 0.0 ? (b - a) / len : vec2(1.0, 0.0);
    vec2 across = vec2(-along.y, along.x);
    vHalf = uWidth * 0.5 * uDensity;
    float reach = vHalf + 1.0;
    float side = float(gl_VertexID & 1) * 2.0 - 1.0;
    vec2 end = (gl_VertexID >> 1) == 0 ? a : b;
    vec2 pos = end * uDensity + across * side * reach;
    vAcross = side * reach;
    gl_Position = vec4(pos.x / uTargetPx.x * 2.0 - 1.0, 1.0 - pos.y / uTargetPx.y * 2.0, 0.0, 1.0);
}
