/*
 * lib/kernel/Kernel.kt: QUANTUM KERNEL's scene and motion, a port of
 * cyberdeck_terminal's (src/wallpaper/quantum-kernel/: params.ts, code.ts,
 * engine.ts), rule for rule: five depths of code in the MATRIX RAIN's
 * glyphs, laid out as code (lanes of indented blocks), scrolling up past
 * each other on black, nearer ones faster; now and then a line in focus
 * lights up as if being run. The dashboard's look as of its 0.27.1 (the
 * user: "It's perfect").
 *
 * Layer: lib (pure Kotlin). The renderer (scene/kernel/KernelRenderer.kt)
 * draws each depth once into a texture (glow and blur baked in) and then,
 * each frame, the five in one pass. Nothing here allocates per frame: the
 * offsets and the run are numbers kept in place, and the line to run is
 * picked by index, not from a filtered list.
 *
 * PHONE (KernelView): the dashboard splits its 1920-px-wide screen into
 * each depth's lanes; a phone's narrow screen gets as many lanes as fit at
 * the dashboard's lane width (at least one), so the code is as dense.
 */
package com.thedungeons2077.fluidmotion.lib.kernel

import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.rain.BASE_GLYPHS
import com.thedungeons2077.fluidmotion.lib.rain.KATAKANA
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One depth of code (params.ts DepthSpec). Sizes in dp (the web's CSS px). */
class DepthSpec(
    /** Its font size, and the line height (× the font). */
    val font: Float,
    val lineHeight: Float,
    /** How fast it scrolls up (dp a second). */
    val speed: Float,
    /** Out of focus: a blur over its glyphs (dp; 0 sharp). */
    val blur: Float,
    /** Its glow's reach (dp) and strength (0–1) at GLOW HIGH. */
    val glowReach: Float,
    val glow: Float,
    /** How opaque its glyphs are. */
    val alpha: Float,
    /** Its colour (KernelTones). */
    val tone: Int,
    /** Lanes across the dashboard's screen, lines a block, rows between blocks, a line's length (glyphs). */
    val lanes: Int,
    val block: IntArray,
    val gap: IntArray,
    val length: IntArray,
    /** The share of the screen's resolution its texture is drawn at: a blurred depth loses nothing drawn smaller. */
    val resolution: Float,
    /** How much heavier its strokes are (dp of outline; 0 the font's own). */
    val weight: Float,
)

/** QUANTUM KERNEL's numbers: the web's (params.ts, 0.27.1), plus the few a phone needs, marked PHONE. */
object KernelParams {
    /** The tones: the far one, the text, the focus (where lines are run), the accent. */
    const val FAR = 0
    const val TEXT = 1
    const val FOCUS = 2
    const val ACCENT = 3

    /** The depths, back to front (the web's 0.27.1). */
    val DEPTHS: List<DepthSpec> = listOf(
        DepthSpec(9f, 1.4f, 6f, 0.5f, 0f, 0f, 0.3f, FAR, 6, intArrayOf(6, 16), intArrayOf(0, 1), intArrayOf(16, 44), 1f, 0f),
        DepthSpec(11f, 1.45f, 10f, 0f, 4f, 0.25f, 0.35f, TEXT, 5, intArrayOf(6, 14), intArrayOf(0, 2), intArrayOf(14, 40), 1f, 0f),
        DepthSpec(15f, 1.5f, 16f, 0f, 7f, 0.3f, 0.45f, TEXT, 4, intArrayOf(5, 12), intArrayOf(1, 3), intArrayOf(14, 36), 1f, 0.25f),
        DepthSpec(24f, 1.45f, 26f, 0.8f, 16f, 0.45f, 0.5f, FOCUS, 3, intArrayOf(5, 12), intArrayOf(1, 3), intArrayOf(14, 32), 1f, 0.7f),
        DepthSpec(38f, 1.4f, 42f, 5f, 24f, 0.4f, 0.2f, ACCENT, 2, intArrayOf(2, 5), intArrayOf(2, 6), intArrayOf(8, 18), 0.35f, 1f),
    )

    /** The depth in focus, where lines are run. */
    val FOCUS_DEPTH = DEPTHS.indexOfFirst { it.tone == FOCUS }

    /** How much more (or less) every glow is at each GLOW level. */
    val GLOW: Map<GlowLevel, Float> = mapOf(GlowLevel.LOW to 0.45f, GlowLevel.MEDIUM to 0.75f, GlowLevel.HIGH to 1f, GlowLevel.MAX to 1.4f)

    /** A monospace glyph's width, as a share of its font size. */
    const val GLYPH_WIDTH = 0.6f

    /** A line in focus lights up: how often one starts (s), how long it lasts (s), how much brighter at its peak. */
    const val RUN_EVERY = 1.4f
    const val RUN_SECONDS = 1.6f
    const val RUN_STRENGTH = 0.6f

    /** PHONE: the dashboard's screen width (CSS px) its lanes are counted for. */
    const val REFERENCE_WIDTH = 1920f

    /** The longest step the motion takes in one frame (s). */
    const val MAX_FRAME_STEP = 0.1f
}

/** One line of code: its row (0 ≤ row < rows), its first column, and its characters (spaces between words). */
class CodeLine(val row: Int, val col: Int, val text: String)

/** code.ts, rule for rule: the code a depth shows. */
object KernelCode {
    /** The joins between words: the rain's own symbols, read as operators. */
    private val JOINS = arrayOf(" = ", "->", " == ", " += ", ": ", ".", " * ", " | ", " ", " ", " ")

    /** A whole number in [r[0], r[1]]. */
    private fun Rng.pick(r: IntArray): Int = r[0] + floor(next() * (r[1] - r[0] + 1)).toInt()

    /** A word of [letters] (2–7 glyphs). */
    private fun word(rng: Rng, letters: List<Char>): String {
        val n = 2 + floor(rng.next() * 6).toInt()
        val sb = StringBuilder()
        repeat(n) { sb.append(letters[floor(rng.next() * letters.size).toInt()]) }
        return sb.toString()
    }

    /** A statement about [length] glyphs long: words joined by operators, now and then in quotes or ending in a colon. */
    fun statement(rng: Rng, letters: List<Char>, length: Int): String {
        val sb = StringBuilder(word(rng, letters))
        while (sb.length < length) {
            val join = JOINS[floor(rng.next() * JOINS.size).toInt()]
            sb.append(join).append(if (rng.next() < 0.12f) "\"${word(rng, letters)}\"" else word(rng, letters))
        }
        if (rng.next() < 0.15f) sb.append(':')
        return sb.toString()
    }

    /**
     * A depth's code: [cols] × [rows] cells (rows loop), [lanes] lanes each
     * running blocks then gaps down the whole loop; a block indented like a
     * function body (deeper after a line ending in `:`, back out now and then).
     */
    fun write(rng: Rng, cols: Int, rows: Int, lanes: Int, spec: DepthSpec, letters: List<Char>): List<CodeLine> {
        val lines = ArrayList<CodeLine>()
        val laneW = cols.toFloat() / lanes
        for (lane in 0 until lanes) {
            // Each lane starts part way into a gap, so the lanes' blocks don't line up.
            var row = floor(rng.next() * (spec.gap[1] + 1)).toInt()
            while (row < rows) {
                val left = floor(lane * laneW + rng.next() * laneW * 0.25f).toInt()
                val n = rng.pick(spec.block)
                var indent = 0
                var i = 0
                while (i < n && row < rows) {
                    val text = statement(rng, letters, rng.pick(spec.length))
                    lines += CodeLine(row, left + indent * 2, text)
                    if (text.endsWith(':')) indent = min(indent + 1, 4) else if (indent > 0 && rng.next() < 0.25f) indent--
                    i++
                    row++
                }
                row += rng.pick(spec.gap)
            }
        }
        return lines
    }

    /** The glyphs words are made of: katakana twice as often (when the phone can draw them), then the rain's digits and letters. */
    fun letters(kana: Boolean): List<Char> {
        val base = BASE_GLYPHS.filter { it.isDigit() || it in 'A'..'Z' }.toList()
        return if (kana) KATAKANA.toList() + KATAKANA.toList() + base else base
    }
}

/**
 * How a screen lays out QUANTUM KERNEL: per depth, a glyph's width and a
 * line's height (px), its lanes (PHONE: as many as fit at the dashboard's
 * lane width), its loop's rows and height (px: at least the screen's, so
 * two copies cover it). [pxPerDp] is zoomed (a zoomed-in screen lays out
 * as if 1/zoom as big).
 */
class KernelView(val width: Float, val height: Float, val pxPerDp: Float) {
    private val depths = KernelParams.DEPTHS
    val colW = FloatArray(depths.size) { depths[it].font * KernelParams.GLYPH_WIDTH * pxPerDp }
    val lineH = FloatArray(depths.size) { depths[it].font * depths[it].lineHeight * pxPerDp }
    val cols = IntArray(depths.size) { ceil(width / colW[it]).toInt() }
    val rows = IntArray(depths.size) { max(8, ceil(height / lineH[it]).toInt() + 2) }
    val loopH = FloatArray(depths.size) { rows[it] * lineH[it] }
    val lanes = IntArray(depths.size) { max(1, ceil(width / pxPerDp * depths[it].lanes / KernelParams.REFERENCE_WIDTH).toInt()) }
}

/** The motion: each depth's scroll (px within its loop) and the line being run. Kept in place, wrapped. */
class KernelMotion(private val view: KernelView, private val focusLines: List<CodeLine>, private val rng: Rng) {
    /** How far up each depth has scrolled (px, within its loop). */
    val offset = FloatArray(KernelParams.DEPTHS.size) { rng.next() * view.loopH[it] }

    /** The line being run (index into the focus depth's lines; -1 none), how far through (s), and the wait for the next (s). */
    var runLine = -1
        private set
    var runT = 0f
        private set
    private var runWait = KernelParams.RUN_EVERY * 0.5f

    /** How bright the run is now (0–RUN_STRENGTH): a sine over its time. */
    val runStrength: Float
        get() = if (runLine < 0) 0f else KernelParams.RUN_STRENGTH * kotlin.math.sin(kotlin.math.PI.toFloat() * runT / KernelParams.RUN_SECONDS)

    /** Moves on by [dt] seconds: the loops scroll (dp a second × px per dp), and a line in focus is run now and then. */
    fun advance(dt: Float) {
        val step = min(dt, KernelParams.MAX_FRAME_STEP)
        val depths = KernelParams.DEPTHS
        for (i in depths.indices) offset[i] = (offset[i] + depths[i].speed * view.pxPerDp * step) % view.loopH[i]
        if (focusLines.isEmpty()) return
        if (runLine >= 0) {
            runT += step
            if (runT >= KernelParams.RUN_SECONDS) runLine = -1
            return
        }
        runWait -= step
        if (runWait > 0f) return
        runWait = KernelParams.RUN_EVERY * (0.6f + rng.next() * 0.8f)
        // A line on screen now: a few tries at random (no list made: nothing allocated per frame).
        val f = KernelParams.FOCUS_DEPTH
        repeat(12) {
            val i = floor(rng.next() * focusLines.size).toInt()
            val y = screenY(f, focusLines[i].row)
            if (y > view.height * 0.05f && y < view.height * 0.9f) {
                runLine = i
                runT = 0f
                return
            }
        }
    }

    /** Where row [row] of depth [d] is on screen now (px from the top, within its loop). */
    fun screenY(d: Int, row: Int): Float {
        val y = (row * view.lineH[d] - offset[d]) % view.loopH[d]
        return if (y < 0f) y + view.loopH[d] else y
    }
}

/** Each depth's colour from the palette (engine.ts kernelTones): pulled toward --cool, the far one dimmed toward the black. */
class KernelTones(c: PaletteColors) {
    private fun rgb(v: Int) = floatArrayOf(((v shr 16) and 255) / 255f, ((v shr 8) and 255) / 255f, (v and 255) / 255f)
    private fun mix(a: FloatArray, b: FloatArray, k: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * k }
    private val bg = rgb(c.bg)
    private val cool = rgb(c.cool)

    /** The background: --bg, true black in every palette (the user, the dashboard's 0.27.1). */
    val back = bg

    /** By tone (KernelParams.FAR … ACCENT). */
    val tones: List<FloatArray> = listOf(
        mix(mix(bg, rgb(c.textDim), 0.8f), cool, 0.3f),
        mix(rgb(c.text), cool, 0.35f),
        mix(rgb(c.accent), cool, 0.25f),
        mix(rgb(c.accent), cool, 0.4f),
    )
}
