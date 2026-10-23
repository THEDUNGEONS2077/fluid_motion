#version 300 es
// rain_ops.vert: one quad per glyph the rain cuts out or draws on its layer
// this frame, in the order the simulation asked (lib/rain/RainSim.kt; its
// fades are rain_fade.vert's). Corners come from gl_VertexID; the rest is
// one instance record each: (column, row), a code (slot + 256 · style +
// 4096 · layer), and an opacity.
layout(location = 0) in vec2 aCell;
layout(location = 1) in float aCode;
layout(location = 2) in float aAlpha;

uniform vec2 uTarget;     // the layer's size (px)
uniform vec2 uPitch[3];   // each depth layer's column and row pitch (px, unrounded)
uniform vec2 uCell[3];    // each depth layer's cell (px, rounded up)
uniform float uOrigin[3]; // where each depth layer's slots start in the atlas (y, px)
uniform vec4 uPads;       // margins round a cell: head (and halo), body (and dim), erase, slot
uniform vec2 uAtlas;      // the atlas's size (px)
uniform int uPerRow;      // slots per atlas row

out vec2 vUv;
flat out int vStyle;
flat out float vAlpha;

const int HEAD = 0;
const int HALO = 3;
const int ERASE = 4;

void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    int code = int(aCode + 0.5);
    int style = (code >> 8) & 15;
    vStyle = style;
    vAlpha = aAlpha;
    int layer = code >> 12;
    int slot = code & 255;
    float pad = (style == HEAD || style == HALO) ? uPads.x : (style == ERASE ? uPads.z : uPads.y);
    vec2 cell = uCell[layer];
    vec2 size = cell + 2.0 * pad;
    // The cell's corner on whole pixels (the web's Math.round), the quad its glow's reach round it.
    vec2 pos = floor(aCell * uPitch[layer] + 0.5) - pad + corner * size;
    // The same part of the glyph's slot (centred in it), pixel for pixel.
    vec2 slotSize = cell + 2.0 * uPads.w;
    vec2 slotAt = vec2(float(slot % uPerRow) * slotSize.x, uOrigin[layer] + float(slot / uPerRow) * slotSize.y);
    vUv = (slotAt + (uPads.w - pad) + corner * size) / uAtlas;
    gl_Position = vec4(pos.x / uTarget.x * 2.0 - 1.0, 1.0 - pos.y / uTarget.y * 2.0, 0.0, 1.0);
}
