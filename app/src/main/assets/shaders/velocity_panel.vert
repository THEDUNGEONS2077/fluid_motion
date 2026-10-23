#version 300 es
// velocity_panel.vert: a slanted panel of light, as its own parallelogram
// (not a box round it: no pixel is shaded that the light can't reach).
// One instance per panel from a uniform array (bottom-left x, width, lean,
// opacity; dp). Panel 0 is the pale one, which spills light round it.
uniform vec2 uTargetPx;   // the target (px)
uniform float uDensity;   // px per dp
uniform vec2 uScreen;     // the screen (dp)
uniform vec4 uPanels[4];

out highp float vU;       // across the panel, in its own unslanted frame (dp)
out highp float vY;       // down the screen (dp)
flat out vec4 vPanel;
flat out int vPale;

void main() {
    vec4 p = uPanels[gl_InstanceID];
    bool pale = gl_InstanceID == 0;
    // The panel's span across (the pale one's includes its spill: 0.6 widths left, 1.6 right).
    float u0 = pale ? p.x - 0.6 * p.y : p.x;
    float u1 = pale ? p.x + 1.6 * p.y : p.x + p.y;
    float u = (gl_VertexID & 1) == 0 ? u0 : u1;
    float y = (gl_VertexID >> 1) == 0 ? 0.0 : uScreen.y;
    vU = u;
    vY = y;
    vPanel = p;
    vPale = pale ? 1 : 0;
    // Its own frame to the screen: x moves right by the lean at the top, none at the bottom.
    vec2 pos = vec2(u + p.z * (1.0 - y / uScreen.y), y) * uDensity;
    gl_Position = vec4(pos.x / uTargetPx.x * 2.0 - 1.0, 1.0 - pos.y / uTargetPx.y * 2.0, 0.0, 1.0);
}
