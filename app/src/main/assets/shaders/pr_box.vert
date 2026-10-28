#version 300 es
// prism box.vert: the glass blocks, one instance each: a unit cube's
// corner (aCorner, 0–1) scaled to the block (aMin, aSize) and placed by the
// camera. Passes the world position, the face's normal and the point's
// place on the block (units from its min corner), for the edges and light.
layout(location = 0) in vec3 aCorner;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aMin;
layout(location = 3) in vec3 aSize;

uniform mat4 uViewProj;

out vec3 vWorld;
out vec3 vLocal;
flat out vec3 vNormal;
flat out vec3 vSize;

void main() {
    vLocal = aCorner * aSize;
    vWorld = aMin + vLocal;
    vNormal = aNormal;
    vSize = aSize;
    gl_Position = uViewProj * vec4(vWorld, 1.0);
}
