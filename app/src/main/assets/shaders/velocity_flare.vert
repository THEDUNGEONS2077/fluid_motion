#version 300 es
// velocity_flare.vert: the corner flare: instance 0 the bloom's square,
// instance 1 the box round the arc of light (worked out on the CPU, dp).
uniform vec2 uTargetPx;   // the target (px)
uniform float uDensity;   // px per dp
uniform vec3 uBloom;      // centre x, y, radius
uniform vec4 uArcBox;     // left, top, right, bottom

out highp vec2 vP;        // where on the screen (dp)
flat out int vPart;

void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vec2 pos = gl_InstanceID == 0
        ? uBloom.xy - uBloom.z + corner * 2.0 * uBloom.z
        : mix(uArcBox.xy, uArcBox.zw, corner);
    vP = pos;
    vPart = gl_InstanceID;
    vec2 px = pos * uDensity;
    gl_Position = vec4(px.x / uTargetPx.x * 2.0 - 1.0, 1.0 - px.y / uTargetPx.y * 2.0, 0.0, 1.0);
}
