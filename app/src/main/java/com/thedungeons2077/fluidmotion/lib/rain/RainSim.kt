/*
 * lib/rain/RainSim.kt: the matrix rain's simulation: drops falling down three
 * layers of columns, and what to draw as they move. A port of
 * cyberdeck_terminal's engine (src/wallpaper/matrix-rain/engine.ts).
 *
 * Layer: lib (pure Kotlin, no Android, no GL). The simulation never draws:
 * it tells a RainOps what changed (a glyph put, a glyph erased, a stripe set
 * faded), and the renderer (wallpaper/rain/RainRenderer.kt) turns that into
 * GPU work. The canvas keeps its pixels between frames, so each frame only
 * draws what moved: a few hundred small copies, whatever the screen size.
 *
 * How it works (as the web engine):
 *   - every column of every layer has one drop slot; a drop falls at its own
 *     speed to its own depth, then waits at the top and falls again;
 *   - a drawn glyph fades with the stripe fades (that *is* the trail);
 *   - a lit head is pale and glowing; when it moves on, its glyph is cut out
 *     by shape and redrawn as body, its glow stays (a soft streak);
 *   - a few trail glyphs are re-rolled each second (the flicker);
 *   - the screen opens full: PREWARM_SECONDS are simulated without drawing,
 *     recording each cell's last glyph, then every visible cell is painted
 *     once at the brightness the fades would have left it.
 * Nothing here allocates per frame: drops live in arrays.
 */
package com.thedungeons2077.fluidmotion.lib.rain

import com.thedungeons2077.fluidmotion.lib.rain.RainParams.FADE_INTERVAL
import com.thedungeons2077.fluidmotion.lib.rain.RainParams.FADE_ORDER
import com.thedungeons2077.fluidmotion.lib.rain.RainParams.FADE_PHASES
import com.thedungeons2077.fluidmotion.lib.rain.RainParams.MUTATIONS_PER_SECOND
import com.thedungeons2077.fluidmotion.lib.rain.RainParams.PREWARM_HALO_MIN_ALPHA
import com.thedungeons2077.fluidmotion.lib.rain.RainParams.PREWARM_MIN_ALPHA
import com.thedungeons2077.fluidmotion.lib.rain.RainParams.PREWARM_REVEAL_ALPHA
import com.thedungeons2077.fluidmotion.lib.rain.RainParams.PREWARM_SECONDS
import com.thedungeons2077.fluidmotion.lib.rain.RainParams.PREWARM_STEP
import com.thedungeons2077.fluidmotion.lib.rain.RainParams.TRAIL_SECONDS
import com.thedungeons2077.fluidmotion.lib.rain.RainParams.fadedAlpha
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Glyph styles: the atlas's colourings (head, body, dim) and the leftover glow of a head (halo). */
object RainStyle {
    const val HEAD = 0
    const val BODY = 1
    const val DIM = 2
    const val HALO = 3
}

/** What the simulation asks the renderer to draw (in this order within a frame: fades, then each layer's erases, then its puts). */
interface RainOps {
    /** Fade one stripe set (rows at FADE_STRIPE · (phase + k · FADE_PHASES)). */
    fun fade(phase: Int)

    /** Cut glyph [glyph]'s shape out of cell (col, row) of [layer]. */
    fun erase(layer: Int, col: Int, row: Int, glyph: Int)

    /** Draw glyph [glyph] in [style] at cell (col, row) of [layer], at [alpha]. */
    fun put(layer: Int, col: Int, row: Int, glyph: Int, style: Int, alpha: Float)
}

/** A small, fast random source (xorshift32), seedable for tests. */
class Rng(seed: Int) {
    private var s = if (seed == 0) 0x9e3779b9.toInt() else seed

    /** A number in [0, 1). */
    fun next(): Float {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        s = x
        return (x ushr 8) / 16777216f
    }

    /** A number in [range]. */
    fun between(range: Span): Float = range.start + (range.endInclusive - range.start) * next()
}

/**
 * A range of floats, its ends kept as plain floats. Kotlin's own float
 * range (a..b) hands its ends out boxed, a new object at every read: drawn
 * from as packets and drops set off, it made garbage every frame.
 */
class Span(val start: Float, val endInclusive: Float) {
    /** Whether [v] is in it. */
    operator fun contains(v: Float): Boolean = v >= start && v <= endInclusive
}

/** No glyph in a cell. */
const val NONE = -1

/** One layer at the current size: its drops (one per column, in arrays) and each cell's last glyph. */
class RainLayerState(val index: Int, val p: RainLayer, val cols: Int, val rows: Int) {
    val y = FloatArray(cols)
    val drawn = IntArray(cols)
    val end = IntArray(cols)
    val speed = FloatArray(cols)
    val alpha = FloatArray(cols)
    val style = IntArray(cols)
    val lit = BooleanArray(cols)
    val glyph = IntArray(cols)
    val wait = FloatArray(cols)
    /** The glyph last drawn in each cell (row · cols + col), or NONE: what to erase when it changes. */
    val glyphAt = IntArray(cols * rows) { NONE }
}

/** The pre-warm's record of one layer: per cell, the last glyph, its style, opacity and time; and when a head left its glow there. */
private class Recording(val cols: Int, val rows: Int) {
    val time = FloatArray(cols * rows) { Float.NEGATIVE_INFINITY }
    val glyph = IntArray(cols * rows)
    val style = IntArray(cols * rows)
    val alpha = FloatArray(cols * rows)
    val halo = FloatArray(cols * rows) { Float.NEGATIVE_INFINITY }
}

/**
 * The rain on a screen of [widthDp] × [heightDp], with [glyphCount] glyphs
 * in the atlas. Call [prewarm] once, then [step] every frame.
 */
class RainSim(val widthDp: Float, val heightDp: Float, val glyphCount: Int, private val rng: Rng) {
    /** The layers, back to front. */
    val layers: List<RainLayerState> = RainParams.LAYERS.mapIndexed { i, p ->
        RainLayerState(i, p, ceil(widthDp / p.colW).toInt(), ceil(heightDp / p.rowH).toInt())
    }

    private var fadeDebt = 0f
    private var fadePhase = 0
    private var recordings: List<Recording>? = null
    private var simTime = 0f

    init {
        for (l in layers) for (c in 0 until l.cols) spawn(l, c, scatter = rng.next() < l.p.occupancy)
    }

    /**
     * A new drop in column [c]. [scatter] places it anywhere along a fall
     * (startup) instead of waiting at the top.
     */
    private fun spawn(l: RainLayerState, c: Int, scatter: Boolean) {
        val p = l.p
        val speed = rng.between(p.speed)
        // Most drops reach the middle, fewer the bottom (END_BIAS).
        val end = max(4, (l.rows * (1 - rng.next().pow(RainParams.END_BIAS)) * 1.08f).roundToInt())
        // Waiting time that keeps `occupancy` of columns busy: falling time × (1 − p)/p, jittered.
        val fall = end / speed
        val wait = if (scatter) 0f else max(rng.between(RainParams.RESPAWN_DELAY), fall * ((1 - p.occupancy) / p.occupancy) * (0.5f + rng.next()))
        val y = if (scatter) rng.next() * end else 0f
        l.y[c] = y
        l.drawn[c] = floor(y).toInt() - 1
        l.end[c] = end
        l.speed[c] = speed
        l.alpha[c] = rng.between(p.alpha)
        l.style[c] = if (rng.next() < 0.55f) RainStyle.DIM else RainStyle.BODY
        l.lit[c] = rng.next() < p.headChance
        l.glyph[c] = (rng.next() * glyphCount).toInt()
        l.wait[c] = wait
    }

    /** Draws (or records) a glyph, and notes it in the cell. */
    private fun put(ops: RainOps, l: RainLayerState, col: Int, row: Int, g: Int, style: Int, alpha: Float) {
        val rec = recordings?.get(l.index)
        if (rec != null) {
            if (row < 0 || row >= rec.rows || col >= rec.cols) return
            val i = row * rec.cols + col
            rec.time[i] = simTime
            rec.glyph[i] = g
            rec.style[i] = style
            rec.alpha[i] = alpha
            return
        }
        ops.put(l.index, col, row, g, style, alpha)
        if (row >= 0 && row < l.rows) l.glyphAt[row * l.cols + col] = g
    }

    /** Cuts the glyph in a cell out (by its own shape), if there is one. */
    private fun erase(ops: RainOps, l: RainLayerState, col: Int, row: Int) {
        if (row < 0 || row >= l.rows) return
        val i = row * l.cols + col
        val g = l.glyphAt[i]
        if (g == NONE) return
        l.glyphAt[i] = NONE
        ops.erase(l.index, col, row, g)
    }

    /** A lit head moves on from (col, row): its glyph becomes body; its glow stays (recorded while pre-warming). */
    private fun dimHead(ops: RainOps, l: RainLayerState, col: Int, row: Int) {
        val rec = recordings?.get(l.index)
        if (rec != null) {
            if (row >= 0 && row < rec.rows && col < rec.cols) rec.halo[row * rec.cols + col] = simTime
        } else {
            erase(ops, l, col, row)
        }
        put(ops, l, col, row, l.glyph[col], l.style[col], l.alpha[col])
    }

    /** Advances the rain by [dt] seconds, telling [ops] what to draw. */
    fun step(dt: Float, ops: RainOps) {
        // 1. The fade: FADE_PHASES stripe sets per FADE_INTERVAL (while recording, time just moves on).
        if (recordings != null) {
            simTime += dt
        } else {
            fadeDebt += (dt / FADE_INTERVAL) * FADE_PHASES
            while (fadeDebt >= 1f) {
                fadeDebt -= 1f
                ops.fade(FADE_ORDER[fadePhase])
                fadePhase = (fadePhase + 1) % FADE_PHASES
            }
        }
        // 2 and 3, layer by layer, back to front (by index: a for over a List makes an iterator every frame).
        for (li in layers.indices) {
            val l = layers[li]
            for (col in 0 until l.cols) {
                if (l.wait[col] > 0f) {
                    l.wait[col] -= dt
                    continue
                }
                l.y[col] += l.speed[col] * dt
                // Draw every row the head reached since the last frame.
                while (l.drawn[col] < floor(l.y[col]).toInt() && l.drawn[col] < l.end[col]) {
                    if (l.lit[col] && l.drawn[col] >= 0) dimHead(ops, l, col, l.drawn[col])
                    l.drawn[col]++
                    l.glyph[col] = (rng.next() * glyphCount).toInt()
                    if (l.lit[col]) put(ops, l, col, l.drawn[col], l.glyph[col], RainStyle.HEAD, 1f)
                    else put(ops, l, col, l.drawn[col], l.glyph[col], l.style[col], l.alpha[col])
                }
                // The flicker (not while recording: it only changes which glyph).
                val trail = min(l.drawn[col], (l.speed[col] * TRAIL_SECONDS).roundToInt())
                if (recordings == null && trail > 1 && rng.next() < MUTATIONS_PER_SECOND * dt * (trail / 12f)) {
                    val back = 1 + (rng.next() * (trail - 1)).toInt()
                    val row = l.drawn[col] - back
                    erase(ops, l, col, row)
                    put(ops, l, col, row, (rng.next() * glyphCount).toInt(), l.style[col], fadedAlpha(l.alpha[col], back / l.speed[col]))
                }
                // Reached its depth: the head dims, and a new drop waits at the top.
                if (l.drawn[col] >= l.end[col]) {
                    if (l.lit[col]) dimHead(ops, l, col, l.drawn[col])
                    spawn(l, col, scatter = false)
                }
            }
        }
    }

    /**
     * Fills the screen: simulates PREWARM_SECONDS without drawing, then paints
     * every recorded cell still visible once, at the brightness the fades
     * would have left it: the bright glyphs, then the faint ones and the
     * heads' leftover glows. Sets each cell's glyph for later erases.
     */
    fun prewarm(ops: RainOps) {
        val recs = layers.map { Recording(it.cols, it.rows) }
        recordings = recs
        simTime = 0f
        var left = PREWARM_SECONDS
        while (left > 0f) {
            step(PREWARM_STEP, ops)
            left -= PREWARM_STEP
        }
        recordings = null
        for (pass in 0..1) {
            for (l in layers) {
                val r = recs[l.index]
                for (i in 0 until r.cols * r.rows) {
                    val age = simTime - r.time[i]
                    if (!(age < TRAIL_SECONDS)) continue
                    val a = fadedAlpha(r.alpha[i], age)
                    val col = i % r.cols
                    val row = i / r.cols
                    val bright = a >= PREWARM_REVEAL_ALPHA
                    if (pass == 0) {
                        if (bright) ops.put(l.index, col, row, r.glyph[i], r.style[i], a)
                        continue
                    }
                    val haloAge = simTime - r.halo[i]
                    val glow = if (haloAge < TRAIL_SECONDS) fadedAlpha(1f, haloAge) else 0f
                    if (glow >= PREWARM_HALO_MIN_ALPHA) ops.put(l.index, col, row, r.glyph[i], RainStyle.HALO, glow)
                    if (!bright && a >= PREWARM_MIN_ALPHA) ops.put(l.index, col, row, r.glyph[i], r.style[i], a)
                }
            }
        }
        // Each cell's glyph, for erasing it later: what the paint left on the screen.
        for (l in layers) {
            val r = recs[l.index]
            for (i in 0 until r.cols * r.rows) {
                val age = simTime - r.time[i]
                l.glyphAt[i] = if (age < TRAIL_SECONDS && fadedAlpha(r.alpha[i], age) >= PREWARM_MIN_ALPHA) r.glyph[i] else NONE
            }
        }
    }
}
