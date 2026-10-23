/*
 * lib/flow/FlowFrame.kt: DATA FLOW as the shaders take it: the board's
 * instances (built once per board, palette and glow) and the light's (each
 * frame, into arrays made once), what scene/flow/FlowRenderer.kt draws and
 * the JVM frame dump hands tools/shader-check.
 *
 * Layer: lib (pure Kotlin). Three kinds of instance, all in dp:
 *   - SEG, a stretch of trace: its ends, colour and opacity, half widths
 *     of its core and halo, the halo's strength and whether it's soft (a
 *     glow) or flat (a packet's halo, as the web strokes it), its distance
 *     from a packet's head at each end and the packet's tail (0: no fade),
 *     and a cap (how far it runs past its ends, to fill a bend's corner);
 *   - MARK, a round or square mark: a via, a pad, a packet's head, a flash;
 *   - CHIP, a chip: its body and whether it's the main one, its glow.
 * Colours come from the palette as the web's flowTones() makes them.
 */
package com.thedungeons2077.fluidmotion.lib.flow

import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.flow.FlowParams.PITCH
import com.thedungeons2077.fluidmotion.lib.mixRgb
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** DATA FLOW's colours (0xRRGGBB), as the web's flowTones(). */
data class FlowTones(
    val dot: Int,
    val warmTrace: Int,
    val coolTrace: Int,
    val warmVia: Int,
    val coolVia: Int,
    val chipBody: Int,
    val chipEdge: Int,
    val die: Int,
    val warmLight: Int,
    val warmHead: Int,
    val coolLight: Int,
    val coolHead: Int,
)

/** The board's colours from a palette: traces, vias, chips, and the packets' light (warm and cool). */
fun flowTones(c: PaletteColors) = FlowTones(
    dot = mixRgb(c.bg, c.line, 0.9f),
    warmTrace = mixRgb(c.line, c.textDim, 0.5f),
    coolTrace = mixRgb(c.bg, c.cool, 0.6f),
    warmVia = mixRgb(c.line, c.textDim, 0.75f),
    coolVia = mixRgb(c.bg, c.cool, 0.85f),
    chipBody = mixRgb(c.bg, c.line, 0.4f),
    chipEdge = mixRgb(c.line, c.textDim, 0.6f),
    die = mixRgb(c.line, c.textDim, 0.25f),
    warmLight = c.accent,
    warmHead = c.textBright,
    coolLight = mixRgb(c.cool, 0xffffff, 0.35f),
    coolHead = mixRgb(c.cool, 0xffffff, 0.8f),
)

/** DATA FLOW's packing. */
object FlowFrame {
    /** Floats per instance of each kind. */
    const val SEG = 16
    const val MARK = 6
    const val CHIP = 6

    /** Mark kinds. */
    const val VIA = 0
    const val PAD = 1
    const val HEAD = 2
    const val FLASH = 3

    /** A via's radius and a pad's half side (dp); a flash's radius (dp). */
    const val VIA_R = PITCH * 0.3f
    const val PAD_HALF = PITCH * 0.275f
    const val FLASH_R = PITCH * 0.9f

    /** Stretches of trace a packet can cover at most (its tail runs round bends): for sizing arrays. */
    const val SEGS_PER_PULSE = 14

    /** How many SEG instances the board takes (its traces' stretches and the pins' stubs). */
    fun boardSegCount(b: FlowBoard): Int = b.traces.sumOf { it.len.size - 1 } + b.chips.sumOf { it.pins.size }

    /** How many SEG instances the light can take at most on [b] (every packet round its bends, every trace aglow). */
    fun lightSegCapacity(b: FlowBoard): Int = FlowParams.MAX_PULSES * SEGS_PER_PULSE + b.traces.sumOf { it.len.size - 1 }

    /** Writes one SEG at [i] of [out]. */
    private fun seg(
        out: FloatArray, i: Int, x0: Float, y0: Float, x1: Float, y1: Float, rgb: Int, alpha: Float,
        halfCore: Float, halfHalo: Float, haloAlpha: Float, soft: Boolean, along0: Float, along1: Float, tail: Float, cap: Float,
    ) {
        val o = i * SEG
        out[o] = x0; out[o + 1] = y0; out[o + 2] = x1; out[o + 3] = y1
        out[o + 4] = ((rgb shr 16) and 255) / 255f; out[o + 5] = ((rgb shr 8) and 255) / 255f; out[o + 6] = (rgb and 255) / 255f; out[o + 7] = alpha
        out[o + 8] = halfCore; out[o + 9] = halfHalo; out[o + 10] = haloAlpha; out[o + 11] = if (soft) 1f else 0f
        out[o + 12] = along0; out[o + 13] = along1; out[o + 14] = tail; out[o + 15] = cap
    }

    /** Writes one MARK at [i] of [out]. */
    private fun mark(out: FloatArray, i: Int, x: Float, y: Float, size: Float, kind: Int, cool: Boolean, alpha: Float) {
        val o = i * MARK
        out[o] = x; out[o + 1] = y; out[o + 2] = size; out[o + 3] = kind.toFloat(); out[o + 4] = if (cool) 1f else 0f; out[o + 5] = alpha
    }

    /**
     * The board's traces and pin stubs as SEG instances: each trace's
     * stretches in its colour, square-capped to fill its bends, with the
     * glow level's soft glow round it (the web's shadowBlur).
     */
    fun boardSegs(b: FlowBoard, tones: FlowTones, glow: FlowGlow): FloatArray {
        val out = FloatArray(boardSegCount(b) * SEG)
        var n = 0
        for (t in b.traces) {
            val colour = if (t.cool) tones.coolTrace else tones.warmTrace
            for (k in 1 until t.len.size) {
                seg(
                    out, n++, t.pts[2 * k - 2] * PITCH, t.pts[2 * k - 1] * PITCH, t.pts[2 * k] * PITCH, t.pts[2 * k + 1] * PITCH, colour, 1f,
                    t.width / 2, if (glow.board > 0f) t.width / 2 + glow.board else 0f, BOARD_GLOW, true, 0f, 0f, 0f, t.width / 2,
                )
            }
        }
        // Pin stubs: from each pin half a point in to the chip's edge.
        for (c in b.chips) {
            for (pin in c.pins) {
                val d = FlowBoard.DIRS[pin.dir]
                seg(
                    out, n++, pin.x * PITCH, pin.y * PITCH, (pin.x - d[0] * 0.5f) * PITCH, (pin.y - d[1] * 0.5f) * PITCH, tones.chipEdge, 1f,
                    0.6f, 0f, 0f, false, 0f, 0f, 0f, 0f,
                )
            }
        }
        return out
    }

    /** The board's vias and pads as MARK instances (at the ends of traces that end in one). */
    fun boardMarks(b: FlowBoard): FloatArray {
        val ends = ArrayList<FloatArray>()
        for (t in b.traces) {
            val n = t.pts.size
            for ((end, x, y) in listOf(Triple(t.start, t.pts[0], t.pts[1]), Triple(t.end, t.pts[n - 2], t.pts[n - 1]))) {
                if (end == End.VIA) ends += floatArrayOf(x * PITCH, y * PITCH, VIA_R, VIA.toFloat(), if (t.cool) 1f else 0f, 1f)
                if (end == End.PAD) ends += floatArrayOf(x * PITCH, y * PITCH, PAD_HALF, PAD.toFloat(), if (t.cool) 1f else 0f, 1f)
            }
        }
        val out = FloatArray(ends.size * MARK)
        ends.forEachIndexed { i, e -> e.copyInto(out, i * MARK) }
        return out
    }

    /** The chips as CHIP instances: body (dp: left, top, width, height), main, glow (0 on the board). */
    fun boardChips(b: FlowBoard): FloatArray {
        val out = FloatArray(b.chips.size * CHIP)
        b.chips.forEachIndexed { i, c ->
            val o = i * CHIP
            out[o] = (c.x - 0.5f) * PITCH; out[o + 1] = (c.y - 0.5f) * PITCH; out[o + 2] = c.w * PITCH; out[o + 3] = c.h * PITCH
            out[o + 4] = if (c.main) 1f else 0f; out[o + 5] = 0f
        }
        return out
    }

    /**
     * This frame's light: into [segs] the afterglow of warm traces and
     * every packet's stretches (fading from the head back over its tail,
     * with its halo), into [marks] the packets' heads and the flashes, into
     * [chips] the chips taking data in. Counts into [counts] (segs, marks,
     * chips); [at] is the caller's own room for a point (each scene's own:
     * several may be drawing at once, on their own threads).
     */
    fun light(sim: FlowSim, tones: FlowTones, glow: FlowGlow, segs: FloatArray, marks: FloatArray, chips: FloatArray, counts: IntArray, at: FloatArray) {
        val b = sim.board
        val cap = segs.size / SEG
        var ns = 0
        var nm = 0
        // Afterglow: traces that just carried data, fading. (Index loops throughout: a for over a List makes an iterator.)
        for (ti in b.traces.indices) {
            val t = b.traces[ti]
            val a = sim.heat[ti]
            if (a < 0.02f) continue
            val colour = if (t.cool) tones.coolLight else tones.warmLight
            for (k in 1 until t.len.size) {
                if (ns >= cap) break
                seg(
                    segs, ns++, t.pts[2 * k - 2] * PITCH, t.pts[2 * k - 1] * PITCH, t.pts[2 * k] * PITCH, t.pts[2 * k + 1] * PITCH, colour, 0.3f * a,
                    (t.width + 0.6f) / 2, 0f, 0f, false, 0f, 0f, 0f, 0f,
                )
            }
        }
        // Packets: the stretches of trace between tail and head.
        for (i in 0 until sim.pulses) {
            if (sim.pWait[i] > 0f) continue
            val t = b.traces[sim.pTrace[i]]
            val len = t.length
            val head = min(sim.pD[i], len)
            val back = max(0f, sim.pD[i] - sim.pTail[i])
            if (head <= back) continue
            val reverse = sim.pReverse[i]
            // Along the trace from its start (a packet running in reverse is mirrored).
            val a = if (reverse) len - head else back
            val z = if (reverse) len - back else head
            val headAt = if (reverse) len - head else head
            val colour = if (t.cool) tones.coolLight else tones.warmLight
            for (k in 1 until t.len.size) {
                val lo = max(a, t.len[k - 1])
                val hi = min(z, t.len[k])
                if (lo >= hi || ns >= cap) continue
                t.pointAt(lo, at)
                val x0 = at[0] * PITCH
                val y0 = at[1] * PITCH
                t.pointAt(hi, at)
                seg(
                    segs, ns++, x0, y0, at[0] * PITCH, at[1] * PITCH, colour, 1f,
                    (t.width + 0.8f) / 2, t.width * glow.halo / 2, glow.haloAlpha, false,
                    abs(headAt - lo) * PITCH, abs(headAt - hi) * PITCH, sim.pTail[i] * PITCH, 0f,
                )
            }
            if (sim.pD[i] <= len && nm < marks.size / MARK) {
                t.pointAt(headAt, at)
                mark(marks, nm++, at[0] * PITCH, at[1] * PITCH, glow.head, HEAD, t.cool, 1f)
            }
        }
        // Vias reached: a ring of light, fading.
        for (f in 0 until sim.flashes) {
            if (nm >= marks.size / MARK) break
            mark(marks, nm++, sim.fX[f] * PITCH, sim.fY[f] * PITCH, FLASH_R, FLASH, sim.fCool[f], sim.fLife[f])
        }
        // Chips taking data in.
        var nc = 0
        for (ci in b.chips.indices) {
            val c = b.chips[ci]
            val g = sim.chipGlow[ci]
            if (g < 0.01f || nc >= chips.size / CHIP) continue
            val o = nc++ * CHIP
            chips[o] = (c.x - 0.5f) * PITCH; chips[o + 1] = (c.y - 0.5f) * PITCH; chips[o + 2] = c.w * PITCH; chips[o + 3] = c.h * PITCH
            chips[o + 4] = if (c.main) 1f else 0f; chips[o + 5] = g
        }
        counts[0] = ns
        counts[1] = nm
        counts[2] = nc
    }

    /** The dot grid's falloff from the main chip: how far its farthest corner is (dp). */
    fun far(b: FlowBoard): Float = hypot(max(b.mainX, b.w - b.mainX), max(b.mainY, b.h - b.mainY))

    /** How strong the board's soft glow is at its brightest (the web's shadow, 0.7 of the trace colour, spread thin). */
    const val BOARD_GLOW = 0.3f
}
