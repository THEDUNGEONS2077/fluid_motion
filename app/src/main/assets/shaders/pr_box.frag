#version 300 es
// prism box.frag: a face of smoky glass. Its body is dark, tinted by the
// light pools near it (magenta and blue: the reference's light inside and
// behind the glass), brighter at grazing angles (fresnel), fading into the
// dark with distance. Its edges are thin lit lines: how far a pixel is from
// the face's edge (units), over how fast that changes across the screen
// (its gradient's length), gives px, so every edge is the same smooth width
// at any distance and angle (as HORIZON's lines). Each line sits wholly
// inside its face (its centre a core-width in), and the face's own outline
// fades out over its last device px: without multisampling, a triangle's
// edge is a hard step, and the bright glass against the dark stepped
// (0.23.1). Premultiplied: the body blends over what's behind; the light
// and the edges add to it.
precision highp float;

const int MAX_LIGHTS = 16;
uniform vec4 uLightPos[MAX_LIGHTS];  // xyz, reach (units)
uniform vec3 uLightCol[MAX_LIGHTS];  // colour × strength × breath
uniform int uLights;
uniform vec3 uEye;
uniform vec3 uBody;      // the glass's own colour (dark navy)
uniform vec3 uEdge;      // the edges' colour
uniform float uAlpha;    // the body's opacity (front faces more than back ones)
uniform float uFresnel;  // how much brighter at grazing angles
uniform float uDensity;  // device px per CSS px
uniform float uEdgeW;    // the edge's core width (CSS px)
uniform float uEdgeGlow; // the edge glow's reach (CSS px)
uniform float uGlowAmt;  // the edge glow's strength (GLOW level)
uniform float uFar;      // where faces have faded to the dark (units from the eye)

in vec3 vWorld;
in vec3 vLocal;
flat in vec3 vNormal;
flat in vec3 vSize;
out vec4 fragColor;

void main() {
    // The light pools here.
    vec3 light = vec3(0.0);
    for (int i = 0; i < MAX_LIGHTS; i++) {
        if (i >= uLights) break;
        vec3 d = vWorld - uLightPos[i].xyz;
        light += uLightCol[i] * exp(-dot(d, d) / (uLightPos[i].w * uLightPos[i].w));
    }
    vec3 v = normalize(uEye - vWorld);
    float facing = abs(dot(vNormal, v));
    float fres = pow(1.0 - facing, 3.0);
    // Tops catch more of the light (the reference's lit upper faces).
    float catchLight = vNormal.y > 0.5 ? 0.85 : 0.55;

    // Distance to the face's nearest edge (units): along the two axes the face spans.
    vec3 a = abs(vNormal);
    vec3 e = min(vLocal, vSize - vLocal);
    float d = a.x > 0.5 ? min(e.y, e.z) : (a.y > 0.5 ? min(e.x, e.z) : min(e.x, e.y));
    float g = length(vec2(dFdx(d), dFdy(d)));
    float dev = d / max(g, 1e-6);  // device px to the face's edge
    float px = dev / uDensity;     // CSS px
    float off = px - uEdgeW;       // from the line's centre, a core-width in
    float core = exp(-0.5 * pow(off / (0.6 * uEdgeW), 2.0));
    float glow = uGlowAmt * exp(-0.5 * pow(off / uEdgeGlow, 2.0));
    // The outline: full cover a device px in, none at the triangle's edge.
    float cover = clamp(dev, 0.0, 1.0);

    // Fading into the dark with distance (the far blocks sink into the navy).
    float fade = 1.0 - smoothstep(uFar * 0.55, uFar, length(uEye - vWorld));

    float alpha = uAlpha * (0.8 + 0.2 * fres);
    vec3 body = uBody * 0.7 * alpha;
    vec3 add = (light * catchLight + uEdge * 0.25 * fres * uFresnel) * fade;
    add += (uEdge + light * 0.6) * (core + glow) * fade;
    fragColor = vec4(body + add, alpha) * cover;
}
