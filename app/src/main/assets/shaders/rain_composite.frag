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
    // The haze (.wallpaper-haze, 0.8.1 / the web's 0.17.6): one smooth curve down the whole
    // height, (1 + cos πt) / 2 of accent at 7%: greenest at the top, gone at the bottom, flat
    // at both ends, no corner anywhere (its old stops at 55% and 95% read as lines).
    vec3 under = mix(uBg, uAccent, 0.035 * (1.0 + cos(3.14159265 * vPos.y)));
    // The rain over it.
    vec4 r = texture(uRain, vec2(vPos.x, 1.0 - vPos.y));
    vec3 c = r.rgb + under * (1.0 - r.a);
    // The floor (.wallpaper-floor, likewise): along t³ over the whole height, nothing at the
    // top, easing in, the background at the bottom edge (the bottom 16% alone showed its start
    // as a straight line across the screen).
    c = mix(c, uBg, vPos.y * vPos.y * vPos.y);
    fragColor = vec4(c + (noise(gl_FragCoord.xy) - 0.5) / 255.0, 1.0);
}
