#version 300 es
// horizon sky.frag: everything above the land, per pixel: the dark plum
// sky, the glow on the horizon and its tall haze dome, the white-hot core,
// darker toward the top corners. Nothing in it moves, so it's drawn once
// (engine.ts): the land is drawn over the bottom half each frame, its far
// end fading into the very same haze (the function below is the same in
// land.frag).
// One set of shaders: cyberdeck_terminal src/wallpaper/horizon/shaders/*, and later fluid_motion's own copy, byte for byte.
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
    fragColor = vec4(c, 1.0);
}
