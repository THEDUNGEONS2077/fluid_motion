/*
 * RainSimTest: the rain's simulation asks for the right drawing: it opens
 * full, erases only the glyph it drew in a cell, keeps puts on its grid,
 * spreads the fades evenly, and repeats itself for a seed.
 */
package com.thedungeons2077.fluidmotion.lib.rain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RainSimTest {
    /** A screen model: what each cell has in it, checked as the ops come (and every op, for comparing runs). */
    private class Screen(sim: RainSim) : RainOps {
        val cells = sim.layers.map { IntArray(it.cols * it.rows) { NONE } }
        val sizes = sim.layers.map { it.cols to it.rows }
        val maxRows = sim.layers.map { it.rows * 1.08 + 2 }
        val fades = IntArray(RainParams.FADE_PHASES)
        val log = StringBuilder()
        var puts = 0
        var erases = 0

        override fun fade(phase: Int) {
            fades[phase]++
            log.append('f').append(phase)
        }

        override fun erase(layer: Int, col: Int, row: Int, glyph: Int) {
            val (cols, rows) = sizes[layer]
            assertTrue("erase in the grid", col in 0 until cols && row in 0 until rows)
            // Cut out by shape: it must be the glyph drawn there last.
            assertEquals("erase names the cell's glyph", cells[layer][row * cols + col], glyph)
            cells[layer][row * cols + col] = NONE
            erases++
            log.append('e').append(layer).append(',').append(col).append(',').append(row)
        }

        override fun put(layer: Int, col: Int, row: Int, glyph: Int, style: Int, alpha: Float) {
            val (cols, rows) = sizes[layer]
            assertTrue("put in the columns", col in 0 until cols)
            assertTrue("put above the deepest drop", row >= 0 && row < maxRows[layer])
            assertTrue("a glyph of the atlas", glyph in 0 until GLYPHS)
            assertTrue("a style", style in RainStyle.HEAD..RainStyle.HALO)
            assertTrue("alpha in (0, 1]: $alpha", alpha > 0f && alpha <= 1f)
            if (row < rows) cells[layer][row * cols + col] = glyph
            puts++
            log.append('p').append(layer).append(',').append(col).append(',').append(row).append(',').append(glyph)
        }
    }

    private fun sim(seed: Int = 1) = RainSim(412f, 915f, GLYPHS, Rng(seed))

    @Test
    fun `the screen opens full, and the cells know their glyphs`() {
        val s = sim()
        val screen = Screen(s)
        s.prewarm(screen)
        assertEquals("no fades while pre-warming", 0, screen.fades.sum())
        assertEquals("no erases while pre-warming", 0, screen.erases)
        for (l in s.layers) {
            val filled = l.glyphAt.count { it != NONE }.toFloat() / l.glyphAt.size
            // The far layer is nearly always raining (occupancy 0.95): most of its cells show a glyph.
            if (l.index == 0) assertTrue("far layer filled: $filled", filled > 0.25f)
            assertTrue("layer ${l.index} has rain: $filled", filled > 0.02f)
            // Every cell the sim thinks has a glyph was painted with it.
            for (i in l.glyphAt.indices) if (l.glyphAt[i] != NONE) assertEquals(l.glyphAt[i], screen.cells[l.index][i])
        }
    }

    @Test
    fun `a minute of rain erases only what it drew, on its grid`() {
        val s = sim(5)
        val screen = Screen(s)
        s.prewarm(screen)
        repeat(60 * 30) { s.step(1f / 30f, screen) }
        assertTrue(screen.puts > 10_000)
        assertTrue(screen.erases > 1_000)
    }

    @Test
    fun `fades come evenly, every stripe set as often`() {
        val s = sim()
        val screen = Screen(s)
        s.prewarm(screen)
        // 1 s at 30 frames a second: 60 fades (12 sets each 0.2 s), each set 5 times.
        repeat(30) { s.step(1f / 30f, screen) }
        val total = screen.fades.sum()
        assertTrue("fades: $total", abs(total - 60) <= 1)
        for (n in screen.fades) assertTrue("each set ~5: ${screen.fades.toList()}", n in 4..6)
        assertEquals(RainParams.FADE_PHASES, RainParams.FADE_ORDER.toSet().size)
    }

    @Test
    fun `a glyph fades to 2 percent over the trail`() {
        assertEquals(0.02f, RainParams.fadedAlpha(1f, RainParams.TRAIL_SECONDS), 0.002f)
        assertEquals(1f, RainParams.fadedAlpha(1f, 0.19f), 0f)
    }

    @Test
    fun `the same seed gives the same rain`() {
        fun run(seed: Int): String {
            val s = sim(seed)
            val screen = Screen(s)
            s.prewarm(screen)
            repeat(90) { s.step(1f / 30f, screen) }
            return screen.log.toString()
        }
        assertEquals(run(3), run(3))
        assertTrue(run(3) != run(4))
    }

    private companion object {
        const val GLYPHS = 64
    }
}
