/*
 * lib/rain/RainRecords.kt: the rain's drawing as GPU instance records: what
 * scene/rain/RainRenderer.kt sends each frame, and what the JVM frame dump
 * hands tools/shader-check (so both draw exactly the same thing).
 *
 * Layer: lib (pure Kotlin). A record is four floats, rain_ops.vert's
 * instance: (column, row) or a fade stripe's top in px, a code (atlas slot
 * + 256 · style + 4096 · layer), and an opacity.
 */
package com.thedungeons2077.fluidmotion.lib.rain

/** Where records go (one call per record: no allocation, no boxing). */
fun interface RecordSink {
    fun record(x: Float, y: Float, code: Float, alpha: Float)
}

/** The styles the renderer draws besides the simulation's (RainStyle 0–3). */
object RainCode {
    /** A glyph cut out by its own shape. */
    const val ERASE = 4

    /** A fade stripe. */
    const val FADE = 5

    /** The code of [slot] in [style] on [layer]. */
    fun of(slot: Int, style: Int, layer: Int): Float = (slot + (style shl 8) + (layer shl 12)).toFloat()
}

/**
 * The atlas slot of each glyph number: the glyph list is [kana] katakana
 * twice, then [base] others (glyphAtlas.ts glyphSet: katakana dominate the
 * reference), the atlas holds each once.
 */
fun glyphSlots(kana: Int, base: Int): IntArray = IntArray(kana * 2 + base) { g -> if (g < kana * 2) g % kana else g - kana }

/** Turns the simulation's drawing into records for [sink]. */
class RainRecorder(private val sink: RecordSink) : RainOps {
    /** The layer's height (px): fade stripes run down to it. */
    var layerH = 0

    /** Glyph number → atlas slot (glyphSlots). */
    var slotOf: IntArray = IntArray(0)

    override fun fade(phase: Int) {
        // One stripe set: every FADE_PHASES-th stripe of FADE_STRIPE px, from the phase's offset.
        val period = RainParams.FADE_PHASES * RainParams.FADE_STRIPE
        val code = RainCode.of(0, RainCode.FADE, 0)
        var y = phase * RainParams.FADE_STRIPE
        while (y < layerH) {
            sink.record(0f, y.toFloat(), code, RainParams.FADE_ALPHA)
            y += period
        }
    }

    override fun erase(layer: Int, col: Int, row: Int, glyph: Int) {
        sink.record(col.toFloat(), row.toFloat(), RainCode.of(slotOf[glyph], RainCode.ERASE, layer), 1f)
    }

    override fun put(layer: Int, col: Int, row: Int, glyph: Int, style: Int, alpha: Float) {
        sink.record(col.toFloat(), row.toFloat(), RainCode.of(slotOf[glyph], style, layer), alpha)
    }
}
