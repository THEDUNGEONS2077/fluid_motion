#version 300 es
// horizon land.frag: the land's triangles, drawn as their edges only: thin
// teal lines with a soft glow over dark faces. How far a pixel is from the
// nearest edge comes from its corner weights (vBary) and how fast they
// change across the screen (their gradient), so every line is the same few
// px wide whatever its distance and angle. The nearest are softer and wider (the reference's
// depth of field); the far land fades into the haze (the same as
// sky.frag's), worked out only where there is haze.
// One set of shaders: cyberdeck_terminal src/wallpaper/horizon/shaders/*, and later fluid_motion's own copy, byte for byte.
precision highp float;

uniform vec2 uTarget;
uniform float uDensity;   // px per CSS px (zoomed)
uniform vec2 uSun;
uniform float uGlowR;
uniform vec2 uDome;
uniform vec3 uSkyTop;
uniform vec3 uSkyLow;
uniform vec3 uGlow;
uniform vec3 uCore;       // the core's colour (its bloom is part of the haze)
uniform float uCoreR;     // the core's radius (px)
uniform vec3 uLine;
uniform vec3 uFace;
uniform float uLineW;     // an edge's width (CSS px)
uniform float uGlowW;     // its glow's width (CSS px)
uniform float uGlowA;     // its glow's strength
uniform float uSoftNear;  // within this distance (units) edges soften
uniform float uSoften;    // how much wider and softer the nearest are

in vec3 vBary;
in float vZ;
in float vFog;

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
    // Px to the nearest edge, straight across it: the weights' change along the steepest way (fwidth over-reads slanted
    // edges, drawing them thinner, in steps: jagged; 0.17.4, the user: smoother, glowing more).
    vec3 bx = dFdx(vBary);
    vec3 by = dFdy(vBary);
    vec3 a = vBary / max(sqrt(bx * bx + by * by), vec3(1e-5));
    float d = min(min(a.x, a.y), a.z);
    // Near the camera: wider and softer, and a little dimmer (out of focus).
    float soft = 1.0 + uSoften * clamp((uSoftNear - vZ) / uSoftNear, 0.0, 1.0);
    // A soft core (a bell across the edge, never a hard pixel step) and a smooth glow round it: a close bloom and a long tail.
    float core = (uLineW * uDensity + 0.45) * soft;
    float line = exp(-0.5 * d * d / (core * core)) * inversesqrt(soft);
    float halo = uGlowW * uDensity * soft;
    float glow = 0.75 * exp(-0.5 * d * d / (halo * halo)) + 0.25 * exp(-d / (halo * 2.5));
    vec3 c = uFace + uLine * (line + uGlowA * glow);
    // Into the haze with distance: the haze's colour only where there's haze to see.
    if (vFog > 0.004) c = mix(c, haze(vec2(gl_FragCoord.x, uTarget.y - gl_FragCoord.y)), vFog);
    fragColor = vec4(c, 1.0);
}
