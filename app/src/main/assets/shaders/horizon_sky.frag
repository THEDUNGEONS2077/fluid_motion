#version 300 es
// horizon_sky.frag: PHONE ONLY. hz_sky.frag exactly (the dashboard's,
// byte for byte, and left as it is: the user, 2026-10-29), plus a dither:
// the glow fades over a few dozen shades of dark red, which an OLED shows
// as rings one shade apart; up to half a shade of fixed noise per pixel
// (interleaved gradient noise, no texture, no clock) breaks them up.
// HorizonSkyTest keeps this equal to hz_sky.frag but for the dither.
precision highp float;

uniform vec2 uTarget;   // the target (px)
uniform vec2 uSun;      // the glow's centre (px, from the top)
uniform float uGlowR;   // the glow's radius (px)
uniform vec2 uDome;     // the haze dome's radii (px)
uniform float uCoreR;   // the core's radius (px)
uniform vec3 uSkyTop;
uniform vec3 uSkyLow;
uniform vec3 uGlow;
uniform vec3 uCore;

out vec4 fragColor;

// The sky and its glow at p (px from the top), with the core's bloom and the darker corners: the same in both shaders, so the far land melts into the sky without a seam.
vec3 haze(vec2 p) {
    float t = clamp(p.y / uTarget.y, 0.0, 1.0);
    vec3 c = mix(uSkyTop, uSkyLow, t * t);
    vec2 d = (p - uSun) / uDome;
    vec2 g = (p - uSun) / uGlowR;
    c += uGlow * (0.42 * exp(-dot(d, d) * 2.2) + 0.85 * exp(-dot(g, g) * 2.4));
    c += uCore * 0.45 * exp(-length(p - uSun) / (uCoreR * 2.2));
    vec2 v = (p / uTarget - vec2(0.5, 0.6)) * vec2(1.0, 1.3);
    return c * (1.0 - 0.3 * smoothstep(0.35, 0.9, length(v)));
}

void main() {
    vec2 p = vec2(gl_FragCoord.x, uTarget.y - gl_FragCoord.y);
    // The haze (with the core's bloom), then the white-hot core itself.
    vec3 c = haze(p) + uCore * (1.0 - smoothstep(uCoreR * 0.2, uCoreR * 1.3, length(p - uSun)));
    // Up to half a shade either way, fixed per pixel: no rings, nothing that moves.
    float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    c += (n - 0.5) / 255.0;
    fragColor = vec4(c, 1.0);
}
