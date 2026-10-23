/*
 * lib/rain/RainRecords.kt: the rain's drawing as GPU instance records: what
 * scene/rain/RainRenderer.kt sends each frame, and what the JVM frame dump
 * hands tools/shader-check (so both draw exactly the same thing).
 *
 * Layer: lib (pure Kotlin). A record is four floats, rain_ops.vert's
 * instance: (column, row), a code (atlas slot + 256 · style + 4096 ·
 * layer), and an opacity. Fades aren't records: a fade is just its stripe
 * set's phase, and rain_fade.vert builds the stripes from gl_InstanceID
 * (about 200 records a frame fewer to fill and send).
 */
package com.thedungeons2077.fluidmotion.lib.rain

/** Where records go (one call per record: no allocation, no boxing). */
fun interface RecordSink {
    fun record(x: Float, y: Float, code: Float, alpha: Float)
}

/** Where fades go: the phase of each stripe set faded, in order. */
fun interface FadeSink {
    fun fade(phase: Int)
}

/** The style the renderer draws besides the simulation's (RainStyle 0–3). */
object RainCode {
    /** A glyph cut out by its own shape. */
    const val ERASE = 4

    /** The code of [slot] in [style] on [layer]. */
    fun of(slot: Int, style: Int, layer: Int): Float = (slot + (style shl 8) + (layer shl 12)).toFloat()
}

/**
 * The atlas slot of each glyph number: the glyph list is [kana] katakana
 * twice, then [base] others (glyphAtlas.ts glyphSet: katakana dominate the
 * reference), the atlas holds each once.
 */
fun glyphSlots(kana: Int, base: Int): IntArray = IntArray(kana * 2 + base) { g -> if (g < kana * 2) g % kana else g - kana }

/**
 * Turns the simulation's drawing into glyph records for [sink] and fades
 * for [fades] (the renderer keeps the two in order: a record after a fade
 * is drawn after it).
 */
class RainRecorder(private val sink: RecordSink, private val fades: FadeSink) : RainOps {
    /** Glyph number → atlas slot (glyphSlots). */
    var slotOf: IntArray = IntArray(0)

    override fun fade(phase: Int) = fades.fade(phase)

    override fun erase(layer: Int, col: Int, row: Int, glyph: Int) {
        sink.record(col.toFloat(), row.toFloat(), RainCode.of(slotOf[glyph], RainCode.ERASE, layer), 1f)
    }

    override fun put(layer: Int, col: Int, row: Int, glyph: Int, style: Int, alpha: Float) {
        sink.record(col.toFloat(), row.toFloat(), RainCode.of(slotOf[glyph], style, layer), alpha)
    }
}
