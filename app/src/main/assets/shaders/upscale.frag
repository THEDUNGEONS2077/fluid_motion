#version 300 es
// upscale.frag: a scene drawn at a lower resolution (QUALITY), scaled up
// to the screen in one pass, smoothly (linear filtering), as the rain's
// composite does with its trail layer.
precision mediump float;

uniform sampler2D uFrame; // the scene at its own resolution (rows bottom up, as GL draws them)

in highp vec2 vPos;
out vec4 fragColor;

void main() {
    fragColor = vec4(texture(uFrame, vec2(vPos.x, 1.0 - vPos.y)).rgb, 1.0);
}
