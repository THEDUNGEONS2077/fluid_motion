/*
 * lib/rain/AtlasLayout.kt: where each glyph sits in the rain's atlas, as
 * numbers: scene/rain/GlyphAtlas.kt draws the glyphs there, rain_ops.vert
 * reads them back from there, and tools/shader-check lays its own atlas out
 * the same way.
 *
 * Layer: lib (pure Kotlin). Each layer has a grid of slots, PER_ROW wide;
 * a slot is the layer's cell (rounded up to whole px) plus a margin as wide
 * as the head glow reaches (the web's rows: GLOW_REACH · blur + 1 dp).
 */
package com.thedungeons2077.fluidmotion.lib.rain

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Digits, a few Latin letters and the symbols from the reference (JetBrains Mono has them all). */
const val BASE_GLYPHS = "0123456789ZTHEMXAKR:.=*+-<>¦|\""

/** Half-width katakana, drawn mirrored as in the film (from the system's CJK font). */
const val KATAKANA = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ"

/** The atlas for [glyphs] distinct glyphs at [pxPerDp], with [glow]. */
class AtlasLayout(val pxPerDp: Float, glow: RainGlow, val glyphs: Int) {
    /** Margins round a cell (px): a head's glow (and a halo's), a body's, an erase's, and the slot's (the largest). */
    val padHead = ceil((glow.head * RainParams.GLOW_REACH + 1) * pxPerDp).toInt()
    val padBody = ceil((glow.body * RainParams.GLOW_REACH + 1) * pxPerDp).toInt()
    val padErase = ceil(pxPerDp).toInt()
    val padSlot = max(padHead, padBody)

    /** Per layer: the cell (px, rounded up), the glyphs' font size (px), where its slots start (y). */
    val cellW = IntArray(LAYERS) { ceil(RainParams.LAYERS[it].colW * pxPerDp).toInt() }
    val cellH = IntArray(LAYERS) { ceil(RainParams.LAYERS[it].rowH * pxPerDp).toInt() }
    val fontPx = IntArray(LAYERS) { (RainParams.LAYERS[it].font * pxPerDp).roundToInt() }
    val originY = IntArray(LAYERS)

    /** The texture's size (px). */
    val width: Int
    val height: Int

    init {
        val rows = (glyphs + PER_ROW - 1) / PER_ROW
        var top = 0
        var widest = 0
        for (l in 0 until LAYERS) {
            originY[l] = top
            top += rows * slotH(l)
            widest = max(widest, PER_ROW * slotW(l))
        }
        width = widest
        height = top
    }

    /** A slot's size on layer [l] (px). */
    fun slotW(l: Int) = cellW[l] + 2 * padSlot
    fun slotH(l: Int) = cellH[l] + 2 * padSlot

    /** The centre of glyph slot [s] on layer [l] (px), where its glyph is drawn. */
    fun centreX(l: Int, s: Int) = (s % PER_ROW) * slotW(l) + slotW(l) / 2f
    fun centreY(l: Int, s: Int) = originY[l] + (s / PER_ROW) * slotH(l) + slotH(l) / 2f

    companion object {
        /** Slots per atlas row (keeps the texture well inside every GPU's size limit). */
        const val PER_ROW = 16

        private val LAYERS = RainParams.LAYERS.size
    }
}
