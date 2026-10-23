#version 300 es
// rain_fade.frag: a fade, black at the fade's strength: with the rain's one
// blend, glBlendFunc(ONE, ONE_MINUS_SRC_ALPHA), that's the web's
// destination-out: what's under it keeps 1 − uFade of itself.
precision mediump float;

uniform float uFade;
out vec4 fragColor;

void main() {
    fragColor = vec4(0.0, 0.0, 0.0, uFade);
}
