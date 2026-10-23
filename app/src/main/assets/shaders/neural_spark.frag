#version 300 es
// neural_spark.frag: a node's sprite, worked out instead of copied: a
// white-hot core tinted with its colour, out to the colour at its edge,
// then the halo fading out (the web's gradient stops, interpolated as
// canvas gradients are: unpremultiplied).
precision mediump float;

uniform float uEdge;  // the core's edge, as a share of the sprite's radius
uniform float uHalo;  // the halo's strength (GLOW level)

in vec2 vLocal;
flat in vec3 vColor;
flat in float vAlpha;
out vec4 fragColor;

void main() {
    float r = length(vLocal);
    vec3 c = vColor;
    float e = uEdge;
    float s1 = 0.55 * e;
    float s3 = min(0.99, 1.6 * e);
    float s4 = min(0.995, 3.0 * e);
    vec4 k;
    if (r < s1) k = mix(vec4(mix(c, vec3(1.0), 0.75), 1.0), vec4(mix(c, vec3(1.0), 0.45), 1.0), r / s1);
    else if (r < e) k = mix(vec4(mix(c, vec3(1.0), 0.45), 1.0), vec4(c, 0.95), (r - s1) / (e - s1));
    else if (r < s3) k = mix(vec4(c, 0.95), vec4(c, uHalo * 0.55), (r - e) / (s3 - e));
    else if (r < s4) k = mix(vec4(c, uHalo * 0.55), vec4(c, uHalo * 0.18), (r - s3) / (s4 - s3));
    else k = mix(vec4(c, uHalo * 0.18), vec4(c, 0.0), clamp((r - s4) / (1.0 - s4), 0.0, 1.0));
    float a = k.a * vAlpha;
    fragColor = vec4(k.rgb * a, a);
}
