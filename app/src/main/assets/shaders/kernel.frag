#version 300 es
// kernel.frag: QUANTUM KERNEL, a whole frame in one pass (the dashboard's
// engine.ts draws its depths' sheets one after another; here one pass reads
// all five). Each depth is a texture of its code's coverage (one channel:
// its glyphs, glow and blur baked in, scene/kernel/KernelRenderer.kt), a
// loop that repeats down the screen; each is read at its own scroll and
// laid over the ones behind in its own colour, back to front, on black
// (premultiplied, as the web's source-over of its sheets). The line being
// run in the focus depth gets its own coverage added again in its colour
// (the web's 'lighter' copy of its patch), fading in and out.
precision highp float;

uniform sampler2D uDepth0;
uniform sampler2D uDepth1;
uniform sampler2D uDepth2;
uniform sampler2D uDepth3;
uniform sampler2D uDepth4;
uniform float uOffset[5];   // each depth's scroll (px)
uniform float uLoopH[5];    // each depth's loop height (px)
uniform vec3 uColour[5];    // each depth's colour
uniform vec2 uScreen;       // the target (px)
uniform vec3 uBack;         // the background (--bg: black)
uniform vec4 uRunRect;      // the line being run (px: left, top, right, bottom)
uniform float uRun;         // how bright it is now (0: none)

in vec2 vPos;
out vec4 fragColor;

// Depth i's coverage here: its loop read at its scroll (the texture repeats down: GL_REPEAT).
float cover(sampler2D t, int i) {
    return texture(t, vec2(vPos.x, (vPos.y * uScreen.y + uOffset[i]) / uLoopH[i])).r;
}

void main() {
    vec2 p = vPos * uScreen;
    vec3 c = uBack;
    float a = cover(uDepth0, 0);
    c = c * (1.0 - a) + uColour[0] * a;
    a = cover(uDepth1, 1);
    c = c * (1.0 - a) + uColour[1] * a;
    a = cover(uDepth2, 2);
    c = c * (1.0 - a) + uColour[2] * a;
    // The focus depth, and the line being run over it.
    a = cover(uDepth3, 3);
    c = c * (1.0 - a) + uColour[3] * a;
    float inRun = step(uRunRect.x, p.x) * step(p.x, uRunRect.z) * step(uRunRect.y, p.y) * step(p.y, uRunRect.w);
    c += uColour[3] * a * uRun * inRun;
    a = cover(uDepth4, 4);
    c = c * (1.0 - a) + uColour[4] * a;
    fragColor = vec4(min(c, vec3(1.0)), 1.0);
}
