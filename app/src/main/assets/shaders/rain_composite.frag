#version 300 es
// rain_composite.frag: the rain's frame on the screen, as the web's layers
// stack (app.css): the background, the faint haze, the rain's layer over
// them, the dark floor over that. Everything but the rain is worked out
// here from the palette, so it costs no texture reads.
precision mediump float;

uniform sampler2D uRain; // the rain's layer, premultiplied (its rows run bottom up)
uniform vec3 uBg;
uniform vec3 uAccent;

in highp vec2 vPos;
out vec4 fragColor;

// Noise of ±half a step, so the faint gradients never band (interleaved gradient noise).
highp float noise(highp vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

void main() {
    // The haze (.wallpaper-haze): an ellipse (120% × 60% of the screen, at 50% 10%) of
    // accent at 5% fading out by 70% of its size, over accent at 5% down to 55% fading
    // out by 95%; the layer at 60% opacity.
    vec2 d = (vPos - vec2(0.5, 0.1)) / vec2(1.2, 0.6);
    float a1 = 0.05 * max(0.0, 1.0 - length(d) / 0.7);
    float a2 = 0.05 * (1.0 - clamp((vPos.y - 0.55) / 0.4, 0.0, 1.0));
    vec3 under = mix(uBg, uAccent, 0.6 * (a1 + a2 * (1.0 - a1)));
    // The rain over it.
    vec4 r = texture(uRain, vec2(vPos.x, 1.0 - vPos.y));
    vec3 c = r.rgb + under * (1.0 - r.a);
    // The floor (.wallpaper-floor): the bottom 16% fades to the background.
    c = mix(c, uBg, clamp((vPos.y - 0.84) / 0.16, 0.0, 1.0));
    fragColor = vec4(c + (noise(gl_FragCoord.xy) - 0.5) / 255.0, 1.0);
}
