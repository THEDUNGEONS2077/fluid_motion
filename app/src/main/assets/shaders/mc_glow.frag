#version 300 es
// megacity glow.frag: a patch of haze (soft all over) or a light: a sharp
// core and a halo, along a tail fading behind it. Added (premultiplied,
// no alpha).
// One set of shaders, byte for byte: cyberdeck_terminal src/wallpaper/megacity/shaders/glow.frag, fluid_motion assets/shaders/mc_glow.frag.
precision highp float;

uniform float uDensity;

in vec2 vLocal;
flat in vec4 vPos;
flat in vec4 vColor;
flat in vec4 vShape;
out vec4 fragColor;

void main() {
    if (vShape.z > 0.5) {
        float r = length(vLocal / vPos.zw);
        float a = vColor.a * pow(clamp(1.0 - r, 0.0, 1.0), 2.0);
        fragColor = vec4(vColor.rgb * a, 0.0);
        return;
    }
    // The nearest point on the tail (from the head back), and how far along it is.
    vec2 s = vec2(-vShape.y, 0.0);
    float k = dot(s, s) > 0.0 ? clamp(dot(vLocal, s) / dot(s, s), 0.0, 1.0) : 0.0;
    float dist = length(vLocal - s * k);
    float fade = 1.0 - k;
    float core = clamp(vShape.x * uDensity + 0.5 - dist * uDensity, 0.0, 1.0) * fade;
    float halo = exp(-dist / max(vPos.z * 0.35, 0.5)) * fade * 0.55;
    float a = vColor.a * min(1.0, core + halo);
    fragColor = vec4(vColor.rgb * a, 0.0);
}
