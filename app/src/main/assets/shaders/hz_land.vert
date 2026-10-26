#version 300 es
// horizon land.vert: a point of the land seen by a pinhole camera gliding
// forward. Each strip of triangles is placed at its distance from the
// camera now: as the camera passes a strip it goes out of sight behind the
// near clip, then comes round again at the far end, deep in the haze, so
// the land never ends (land.ts). The far wall (strip −1) stands where it's
// put. How much haze covers a point is worked out here, once per corner,
// not per pixel.
// One set of shaders: cyberdeck_terminal src/wallpaper/horizon/shaders/*, and later fluid_motion's own copy, byte for byte.
precision highp float;

layout(location = 0) in vec2 aPoint;  // across, height (units)
layout(location = 1) in vec4 aStrip;  // strip (−1: the far wall), row in it (0/1), depth offset (rows; the wall's depth in units), corner (0, 1, 2; 3: no edges)

uniform vec2 uTarget;   // the target (px)
uniform float uFocal;   // focal length (px)
uniform float uHorizon; // the horizon (px from the top)
uniform float uCamH;    // the camera's height (units)
uniform float uCamZ;    // how far the camera has come (units, wrapped at uRows × uCell)
uniform float uRows;    // rows of land (it repeats after uRows × uCell units)
uniform float uCell;    // a row's depth (units)
uniform float uNear;    // the near clip (units)
uniform float uFar;     // the far clip (units)
uniform float uFogStart;
uniform float uFogEnd;

out vec3 vBary;
out float vZ;
out float vFog;

void main() {
    // A strip's distance in rows: from just behind the camera (−1, out of sight) to uRows − 1 away; in units, times the cell. The wall's: as given.
    float z = aStrip.x < 0.0 ? aStrip.z : (mod(aStrip.x - uCamZ / uCell + 1.0, uRows) - 1.0 + aStrip.y + aStrip.z) * uCell;
    // Pinhole: on screen at (W/2 + f·x/z, horizon + f·(camH − y)/z), written with w = z so the GPU divides (and clips at the near plane).
    gl_Position = vec4(
        2.0 * uFocal * aPoint.x / uTarget.x,
        (1.0 - 2.0 * uHorizon / uTarget.y) * z - 2.0 * uFocal * (uCamH - aPoint.y) / uTarget.y,
        (z * (uFar + uNear) - 2.0 * uFar * uNear) / (uFar - uNear),
        z);
    int corner = int(aStrip.w + 0.5);
    // Corner 3 (the wall): every weight 1, so no pixel is near an edge.
    vBary = corner == 3 ? vec3(1.0) : vec3(corner == 0 ? 1.0 : 0.0, corner == 1 ? 1.0 : 0.0, corner == 2 ? 1.0 : 0.0);
    vZ = z;
    vFog = sqrt(smoothstep(uFogStart, uFogEnd, z));
}
