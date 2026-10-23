#version 300 es
// flow_bg.frag: DATA FLOW's board under its traces, as the web's CSS and
// dot grid draw it: --cool rising from the bottom left, the --accent
// glowing softly round the main chip, and a 1 dp dot at every grid point,
// brightest round the main chip and fading out (worked out here: no
// geometry, no texture).
precision mediump float;

uniform vec3 uBg;
uniform vec3 uCool;
uniform vec3 uAccent;
uniform vec3 uDot;
uniform highp vec2 uScreen;    // the screen (dp)
uniform highp float uDensity;  // px per dp
uniform highp float uPitch;    // the grid's pitch (dp)
uniform highp vec2 uMain;      // the main chip's centre (dp)
uniform highp float uFar;      // from it to the farthest corner (dp)

in highp vec2 vPos;
out vec4 fragColor;

// Noise of ±half a step, so the dark gradients never band (interleaved gradient noise).
highp float noise(highp vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

void main() {
    // Cool light (70% × 75% at 8% 100%): cool at 14% into the background, out to nothing.
    float t2 = length((vPos - vec2(0.08, 1.0)) / vec2(0.70, 0.75));
    vec3 c = mix(mix(uBg, uCool, 0.14), uBg, clamp(t2, 0.0, 1.0));
    // The accent round the main chip (42% × 46% at 62% 46%): 9% out to nothing.
    float t1 = length((vPos - vec2(0.62, 0.46)) / vec2(0.42, 0.46));
    c = mix(c, uAccent, 0.09 * max(0.0, 1.0 - t1));
    // The dot grid: a 1 dp square at every point.
    highp vec2 dp = vPos * uScreen;
    highp vec2 off = (fract(dp / uPitch + 0.5) - 0.5) * uPitch;
    highp float d = max(abs(off.x), abs(off.y)) * uDensity;
    float cover = clamp(0.5 * uDensity + 0.5 - d, 0.0, 1.0);
    float a = clamp(0.85 - 0.6 * length(dp - uMain) / uFar, 0.0, 1.0);
    c = mix(c, uDot, cover * a);
    fragColor = vec4(c + (noise(gl_FragCoord.xy) - 0.5) / 255.0, 1.0);
}
