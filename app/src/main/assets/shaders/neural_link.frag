#version 300 es
// neural_link.frag: a stroke with a smooth one-pixel edge (as the web's
// antialiased lines), premultiplied.
precision mediump float;

in float vAcross;
flat in vec3 vColor;
flat in float vAlpha;
flat in float vHalf;
out vec4 fragColor;

void main() {
    float cover = clamp(vHalf + 0.5 - abs(vAcross), 0.0, 1.0);
    float a = vAlpha * cover;
    fragColor = vec4(vColor * a, a);
}
