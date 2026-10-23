#version 300 es
// megacity shape.frag: a foreground triangle's colour (premultiplied: its
// fading edge blends smoothly).
// One set of shaders, byte for byte: cyberdeck_terminal src/wallpaper/megacity/shaders/shape.frag, fluid_motion assets/shaders/mc_shape.frag.
precision mediump float;

in vec4 vColor;
out vec4 fragColor;

void main() {
    fragColor = vColor;
}
