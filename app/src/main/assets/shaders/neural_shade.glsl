// neural_shade.glsl: NOT a shader on its own: the palette's colour along the
// neural net's diagonal, pasted into the shaders that need it by
// scene/neural/NeuralRenderer.kt (and by tools/shader-check) where they say
// "#include neural_shade". Mirrors lib/neural/NeuralSim.kt diagonal() and shade().
uniform vec2 uScreen;  // the screen (dp)
uniform vec3 uCool;    // --cool lifted 40% towards white
uniform vec3 uBright;  // --text-bright
uniform vec3 uAccent;  // --accent

vec3 shade(vec2 p) {
    vec2 s = p / uScreen;
    float t = clamp(1.05 * s.y + 0.35 * s.x - 0.2, 0.0, 1.0);
    if (t < 0.3) return mix(uCool, uBright, smoothstep(0.0, 1.0, t / 0.3));
    return mix(uBright, uAccent, smoothstep(0.0, 1.0, min(1.0, (t - 0.3) / 0.34)));
}

// Where a point sits on the diagonal (0 top left … 1 bottom right).
float diagonal(vec2 p) {
    vec2 s = p / uScreen;
    return clamp(1.05 * s.y + 0.35 * s.x - 0.2, 0.0, 1.0);
}
