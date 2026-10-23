#version 300 es
// velocity_light.vert: VELOCITY's thin lights, one quad each from uniform
// arrays (dp): the long faint lines across the screen (instances
// 0 … uLineCount − 1), then the streaks racing past. A line's quad is the
// screen's width and 1 dp tall; a streak's is its tail and its glow
// (4 widths tall).
uniform vec2 uTargetPx;     // the target (px)
uniform float uDensity;     // px per dp
uniform vec2 uScreen;       // the screen (dp)
uniform int uLineCount;
uniform vec4 uLines[10];    // y, opacity
uniform vec4 uStreaks[48];  // head x, y, tail length, thickness
uniform vec4 uStreakLook[48]; // opacity, 1 if pale

out highp vec2 vP;          // where on the screen (dp)
flat out vec4 vShape;       // a line: (0, y, width, 1); a streak: (head x, y, length, thickness)
flat out vec2 vLook;        // opacity, pale; a line's pale is -1
void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vec2 pos;
    if (gl_InstanceID < uLineCount) {
        vec4 l = uLines[gl_InstanceID];
        vShape = vec4(0.0, l.x, uScreen.x, 1.0);
        vLook = vec2(l.y, -1.0);
        pos = vec2(corner.x * uScreen.x, l.x + corner.y);
    } else {
        int i = gl_InstanceID - uLineCount;
        vec4 s = uStreaks[i];
        vShape = s;
        vLook = uStreakLook[i].xy;
        // The tail to the head, and the glow's 4 thicknesses (plus a pixel for the smooth edge).
        float reach = s.w * 2.0 + 1.0 / uDensity;
        pos = vec2(s.x - s.z + corner.x * (s.z + 1.0 / uDensity), s.y - reach + corner.y * 2.0 * reach);
    }
    vP = pos;
    vec2 px = pos * uDensity;
    gl_Position = vec4(px.x / uTargetPx.x * 2.0 - 1.0, 1.0 - px.y / uTargetPx.y * 2.0, 0.0, 1.0);
}
