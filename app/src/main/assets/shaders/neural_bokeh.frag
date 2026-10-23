#version 300 es
// neural_bokeh.frag: an even disc with a soft edge (out of focus): the web's
// sprite gradient, 0.9 → 0.75 at 65% → 0 at the edge, premultiplied.
precision mediump float;

in vec2 vLocal;
flat in vec3 vColor;
flat in float vAlpha;
out vec4 fragColor;

void main() {
    float r = length(vLocal);
    float a = r < 0.65 ? mix(0.9, 0.75, r / 0.65) : mix(0.75, 0.0, clamp((r - 0.65) / 0.35, 0.0, 1.0));
    a *= vAlpha;
    fragColor = vec4(vColor * a, a);
}
