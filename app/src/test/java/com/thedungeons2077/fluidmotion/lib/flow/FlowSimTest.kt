/*
 * FlowSimTest: DATA FLOW's board (traces in runs 45° apart that never cross
 * or touch, chip traces leaving from their pins, the screen covered enough,
 * the same board from the same seed), its motion (pools never overrun,
 * lights stay in 0–1, data does arrive) and its packing (instances within
 * the room made for them; FrameAllocationTest weighs a frame).
 */
package com.thedungeons2077.fluidmotion.lib.flow

import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

class FlowSimTest {
    /** Screens (dp): an S25 Ultra at FHD+, a small old phone, a tablet in landscape. */
    private val screens = listOf(384f to 832f, 360f to 640f, 1280f to 800f)

    /** A grid point as one number (x, y may be a few past the screen's edges). */
    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

    /** Every grid point trace [t] passes through, its runs walked step by step. */
    private fun points(t: Trace): List<Long> {
        val out = ArrayList<Long>()
        out += key(t.pts[0], t.pts[1])
        for (k in 1 until t.pts.size / 2) {
            var x = t.pts[2 * k - 2]
            var y = t.pts[2 * k - 1]
            val dx = Integer.signum(t.pts[2 * k] - x)
            val dy = Integer.signum(t.pts[2 * k + 1] - y)
            while (x != t.pts[2 * k] || y != t.pts[2 * k + 1]) {
                x += dx
                y += dy
                out += key(x, y)
            }
        }
        return out
    }

    @Test
    fun `traces run 45 degrees apart and never cross or touch`() {
        for ((w, h) in screens) for (seed in 1..6) {
            val b = FlowBoard.build(w, h, Rng(seed))
            val owner = HashMap<Long, Int>()
            b.traces.forEachIndexed { ti, t ->
                for (k in 1 until t.pts.size / 2) {
                    val dx = t.pts[2 * k] - t.pts[2 * k - 2]
                    val dy = t.pts[2 * k + 1] - t.pts[2 * k - 1]
                    assertTrue("a run goes somewhere", dx != 0 || dy != 0)
                    assertTrue("runs are straight or at 45°: $dx, $dy", dx == 0 || dy == 0 || abs(dx) == abs(dy))
                }
                for (p in points(t)) {
                    val other = owner.put(p, ti)
                    assertTrue("a point on two traces ($w × $h, seed $seed)", other == null || other == ti)
                }
            }
            // Touching: no trace's point is beside another's (a free point always between them).
            for ((p, ti) in owner) {
                val x = (p shr 32).toInt()
                val y = p.toInt()
                for ((ox, oy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val next = owner[key(x + ox, y + oy)]
                    assertTrue("traces $ti and $next touch at $x, $y", next == null || next == ti)
                }
            }
        }
    }

    @Test
    fun `chips have their traces, loose traces fill the screen, and a seed gives the same board`() {
        for ((w, h) in screens) for (seed in 1..6) {
            val b = FlowBoard.build(w, h, Rng(seed))
            assertEquals(1, b.chips.count { it.main })
            assertTrue("side chips: ${b.chips.size - 1}", b.chips.size >= 2)
            val margin = FlowParams.MARGIN
            val sw = ceil(w / FlowParams.PITCH).toInt()
            val sh = ceil(h / FlowParams.PITCH).toInt()
            for (t in b.traces) {
                for (k in 0 until t.pts.size / 2) {
                    assertTrue(t.pts[2 * k] in -margin..sw + margin && t.pts[2 * k + 1] in -margin..sh + margin)
                }
                if (t.chip >= 0) {
                    // Out from one of its chip's pins, long enough not to be a stub.
                    assertEquals(End.CHIP, t.start)
                    assertTrue(b.chips[t.chip].pins.any { it.x == t.pts[0] && it.y == t.pts[1] })
                    assertTrue("chip trace length ${t.length}", t.length >= FlowParams.MIN_STEPS)
                } else {
                    assertTrue(t.start != End.CHIP && t.end != End.CHIP)
                }
            }
            b.buses.forEachIndexed { bi, bus -> for (ti in bus) assertEquals(bi, b.traces[ti].bus) }
            // Covered: the share of the screen's points with a trace on them.
            val onScreen = b.traces.flatMap { points(it) }.toSet().count { p ->
                val x = (p shr 32).toInt()
                val y = p.toInt()
                x in 0..sw && y in 0..sh
            }
            val covered = onScreen.toFloat() / ((sw + 1) * (sh + 1))
            assertTrue("covered $covered ($w × $h, seed $seed)", covered >= FlowParams.COVERAGE)
            val again = FlowBoard.build(w, h, Rng(seed))
            assertEquals(b.traces.size, again.traces.size)
            b.traces.indices.forEach { assertArrayEquals(b.traces[it].pts, again.traces[it].pts) }
        }
    }

    @Test
    fun `data runs, flashes and lights chips, within its pools`() {
        for ((w, h) in screens) {
            val sim = FlowSim(FlowBoard.build(w, h, Rng(5)), Rng(6))
            var flashed = 0
            var glowed = 0f
            var running = 0
            repeat(30 * 120) {
                sim.advance(1f / 30f)
                assertTrue(sim.pulses <= FlowParams.MAX_PULSES && sim.flashes <= FlowParams.MAX_FLASHES)
                for (i in 0 until sim.pulses) assertTrue(sim.pD[i] >= 0f && sim.pTail[i] > 0f)
                for (f in 0 until sim.flashes) assertTrue(sim.fLife[f] > 0f && sim.fLife[f] <= 1f)
                for (v in sim.heat) assertTrue(v in 0f..1f)
                for (v in sim.chipGlow) assertTrue(v in 0f..1f)
                flashed = max(flashed, sim.flashes)
                glowed = max(glowed, sim.chipGlow.max())
                running = max(running, sim.pulses)
            }
            assertTrue("packets $running", running >= 3)
            assertTrue("flashes $flashed", flashed >= 1)
            assertTrue("chip glow $glowed", glowed > 0.3f)
        }
    }

    @Test
    fun `the board and light fit their instances`() {
        val tones = flowTones(Palettes.ICE.colors)
        for ((w, h) in screens) for (linkGlow in listOf(true, false)) {
            val glow = FlowParams.glow(GlowLevel.HIGH, linkGlow)
            val b = FlowBoard.build(w, h, Rng(9))
            val sim = FlowSim(b, Rng(10))
            assertEquals(FlowFrame.boardSegCount(b) * FlowFrame.SEG, FlowFrame.boardSegs(b, tones, glow).size)
            val ends = b.traces.sumOf { t -> listOf(t.start, t.end).count { it == End.VIA || it == End.PAD } }
            assertEquals(ends * FlowFrame.MARK, FlowFrame.boardMarks(b).size)
            assertEquals(b.chips.size * FlowFrame.CHIP, FlowFrame.boardChips(b).size)
            val segs = FloatArray(FlowFrame.lightSegCapacity(b) * FlowFrame.SEG)
            val marks = FloatArray((FlowParams.MAX_PULSES + FlowParams.MAX_FLASHES) * FlowFrame.MARK)
            val chips = FloatArray(b.chips.size * FlowFrame.CHIP)
            val counts = IntArray(3)
            val at = FloatArray(2)
            var most = 0
            repeat(30 * 60) {
                sim.advance(1f / 30f)
                FlowFrame.light(sim, tones, glow, segs, marks, chips, counts, at)
                // The room is never short: nothing is ever dropped.
                assertTrue(counts[0] < segs.size / FlowFrame.SEG)
                most = max(most, counts[0])
                for (i in 0 until counts[0]) {
                    val o = i * FlowFrame.SEG
                    for (k in 0 until FlowFrame.SEG) assertTrue(segs[o + k].isFinite())
                    val tail = segs[o + 14]
                    // A packet's stretch fades from its head back over its tail, never past it.
                    if (tail > 0f) assertTrue(segs[o + 12] in 0f..tail + 1e-3f && segs[o + 13] in 0f..tail + 1e-3f)
                    if (!linkGlow) assertEquals(0f, segs[o + 10], 0f)
                }
                for (i in 0 until counts[1]) assertTrue(marks[i * FlowFrame.MARK + 5] in 0f..1f)
                for (i in 0 until counts[2]) assertTrue(chips[i * FlowFrame.CHIP + 5] in 0f..1f)
            }
            assertTrue("light stretches $most", most > 10)
        }
    }

    @Test
    fun `colours come from the palette as the web mixes them`() {
        val c = Palettes.ICE.colors
        val t = flowTones(c)
        assertEquals(c.accent, t.warmLight)
        assertEquals(c.textBright, t.warmHead)
        // The cool light lifted towards white, the head more so.
        assertTrue((t.coolHead and 255) >= (t.coolLight and 255))
        // The board darker than its light: traces dim, chips dark.
        val lum = { rgb: Int -> (rgb shr 16 and 255) + (rgb shr 8 and 255) + (rgb and 255) }
        assertTrue(lum(t.warmTrace) < lum(t.warmLight) && lum(t.chipBody) < lum(t.chipEdge))
    }
}
