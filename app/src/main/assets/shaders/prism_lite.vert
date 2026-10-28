#version 300 es
// prism_lite.vert: PHONE ONLY (PRISM at LOW and MEDIUM, for slower phones).
// pr_box.vert, drawn on a finer cube (lib/prism/Prism.kt gridCubeVertices:
// a grid of quads on each face), plus the light pools' sum, here at each
// grid corner instead of at every pixel (prism_lite.frag blends between
// them). The pools are wide and soft next to a grid cell, so it looks the
// same for far less work. pr_box.vert itself is the web's, byte for byte,
// and stays so.
layout(location = 0) in vec3 aCorner;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aMin;
layout(location = 3) in vec3 aSize;

uniform mat4 uViewProj;
const int MAX_LIGHTS = 16;
uniform vec4 uLightPos[MAX_LIGHTS];  // xyz, reach (units)
uniform vec3 uLightCol[MAX_LIGHTS];  // colour × strength × breath
uniform int uLights;

out vec3 vWorld;
out vec3 vLocal;
flat out vec3 vNormal;
flat out vec3 vSize;
out vec3 vLight;

void main() {
    vLocal = aCorner * aSize;
    vWorld = aMin + vLocal;
    vNormal = aNormal;
    vSize = aSize;
    // The light pools here: pr_box.frag's sum, at this corner.
    vec3 light = vec3(0.0);
    for (int i = 0; i < MAX_LIGHTS; i++) {
        if (i >= uLights) break;
        vec3 d = vWorld - uLightPos[i].xyz;
        light += uLightCol[i] * exp(-dot(d, d) / (uLightPos[i].w * uLightPos[i].w));
    }
    vLight = light;
    gl_Position = uViewProj * vec4(vWorld, 1.0);
}
