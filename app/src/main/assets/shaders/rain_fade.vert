#version 300 es
// rain_fade.vert: the trail layer's fades, built from gl_InstanceID alone:
// each fade is a set of thin full-width stripes (every FADE_PHASES-th
// stripe of FADE_STRIPE px, from its phase's offset), and only the phases
// are sent (lib/rain/RainRecords.kt). Instance i is stripe i % uPerSet of
// set i / uPerSet; stripes past the layer's bottom fall outside it.
uniform vec2 uTarget;   // the layer's size (px)
uniform int uPhases[8]; // the stripe sets faded, in order
uniform int uPerSet;    // stripes in a set
uniform float uStripe;  // a stripe's height (px)
uniform float uPeriod;  // from one of a set's stripes to the next (px)

void main() {
    int set = gl_InstanceID / uPerSet;
    int k = gl_InstanceID - set * uPerSet;
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vec2 pos = vec2(corner.x * uTarget.x, float(uPhases[set]) * uStripe + float(k) * uPeriod + corner.y * uStripe);
    gl_Position = vec4(pos.x / uTarget.x * 2.0 - 1.0, 1.0 - pos.y / uTarget.y * 2.0, 0.0, 1.0);
}
