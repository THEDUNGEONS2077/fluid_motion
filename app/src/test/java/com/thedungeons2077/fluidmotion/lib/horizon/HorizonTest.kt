/*
 * HorizonTest: HORIZON's land and framing (lib/horizon): the same land for
 * the same seed; every point in its bounds; the mesh listed far end first,
 * every triangle facing the camera (the GPU drops the other side), the
 * wall first; the draw order back to front at any camera distance; the
 * phone's framing (the web's on 16:9, smaller cells on a narrow screen,
 * within the quad budget when zoomed out); the colours from SYNTH. The
 * shaders are drawn by tools/shader-check from FrameDumpTest's dump.
 */
package com.thedungeons2077.fluidmotion.lib.horizon

import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil

class HorizonTest {
    private val p = HorizonParams

    // An upright S25 Ultra (px), and a 16:9 screen like the web's.
    private val phone = HorizonView(1080f, 2340f, 2.8125f, 1f)
    private val wide = HorizonView(1920f, 1080f, 1f, 1f)

    @Test
    fun `same land for the same seed, another for another`() {
        val a = Land.build(Rng(7), 0.5f, 10f)
        assertTrue(Land.build(Rng(7), 0.5f, 10f).y.contentEquals(a.y))
        assertTrue(!Land.build(Rng(8), 0.5f, 10f).y.contentEquals(a.y))
    }

    @Test
    fun `every point in its bounds, mostly low land with some peaks`() {
        val cell = phone.cell
        val land = Land.build(Rng(3), cell, phone.halfWidth)
        val half = (land.cols - 1) / 2f * cell
        assertTrue(half >= phone.halfWidth)
        for (r in 0 until land.rows) for (c in 0 until land.cols) {
            val i = land.at(r, c)
            assertTrue(abs(land.x[i] - (-half + c * cell)) <= p.JITTER_X * cell + 1e-5f)
            assertTrue(abs(land.dz[i]) <= p.JITTER_Z + 1e-5f)
            assertTrue(land.y[i] >= 0f && land.y[i] <= p.HEIGHT_PEAK.endInclusive * cell + 1e-5f)
        }
        val peaks = land.y.count { it > p.HEIGHT_LOW.endInclusive * cell }.toFloat() / land.y.size
        assertTrue("peaks $peaks", peaks in 0.05f..0.3f)
    }

    @Test
    fun `the mesh - the wall first, then strips far end first, every triangle counter-clockwise on screen`() {
        val land = Land.build(Rng(5), 0.6f, 6f)
        val mesh = Land.mesh(land)
        val v = Land.VERTEX
        assertEquals((Land.WALL_VERTICES + land.rows * land.stripVertices) * v, mesh.size)
        for (k in 0 until Land.WALL_VERTICES) assertEquals(-1f, mesh[k * v + 2])
        // Strip numbers fall from the last to the first.
        for (pos in 0 until land.rows) assertEquals((land.rows - 1 - pos).toFloat(), mesh[(Land.WALL_VERTICES + pos * land.stripVertices) * v + 2])
        // Seen from the camera, a near row is lower on screen, x to the right: (x, row + height) as screen coordinates, y up.
        for (t in 0 until mesh.size / v / 3) {
            fun x(k: Int) = mesh[(t * 3 + k) * v]
            fun up(k: Int) = mesh[(t * 3 + k) * v + 3] * 10f + mesh[(t * 3 + k) * v + 1]
            val area = (x(1) - x(0)) * (up(2) - up(0)) - (x(2) - x(0)) * (up(1) - up(0))
            assertTrue("triangle $t winds clockwise", area > 0f)
        }
    }

    @Test
    fun `the draw order covers every strip once, farthest first, at any camera distance`() {
        val land = Land.build(Rng(9), 0.5f, 4f)
        var camZ = 0f
        repeat(200) {
            camZ = advanceCamera(camZ, 0.1f, 1.7f, land)
            val near = HorizonDraw.nearStrip(camZ, land)
            // The strips in the order drawn: the positions [rows − near, rows) then [0, rows − near) hold strips rows − 1 − position.
            val order = (land.rows - near until land.rows).map { land.rows - 1 - it } + (0 until land.rows - near).map { land.rows - 1 - it }
            assertEquals((0 until land.rows).toSet(), order.toSet())
            // Distances as the vertex shader places them: each no farther than the one drawn before.
            val dist = order.map { s -> ((s - camZ / land.cell + 1f).mod(land.rows.toFloat())) - 1f }
            for (i in 1 until dist.size) assertTrue("at $camZ: ${dist[i - 1]} then ${dist[i]}", dist[i] <= dist[i - 1] + 1e-3f)
            assertEquals(Land.WALL_VERTICES + (land.rows - near) * land.stripVertices, HorizonDraw.farFirst(near, land))
        }
    }

    @Test
    fun `framing - the web's on 16 to 9, smaller cells on an upright phone, the same cells across`() {
        assertEquals(1f, wide.narrow, 1e-5f)
        assertEquals(1f, wide.cell, 1e-5f)
        assertEquals(0.95f * 1080f, wide.focal, 1e-3f)
        assertEquals(540f - 1080f * p.SUN_LIFT, wide.sunY, 1e-3f)
        assertEquals(3.85f, phone.narrow, 0.01f)
        assertEquals(1f / phone.narrow, phone.cell, 1e-5f)
        // Cells across the bottom edge: (width / 2) / focal × (distance there) × 2 / cell, the same on both.
        fun bottomCells(v: HorizonView) = v.width / v.focal * (v.focal / (v.height * 0.5f)) / v.cell
        assertEquals(bottomCells(wide), bottomCells(phone), 0.01f)
        // The land reaches past the screen's edges at its far end.
        assertTrue(phone.halfWidth >= phone.width / (2f * phone.focal) * p.DEPTH)
        // A cell passes as often as on the web.
        assertEquals(wide.speed / wide.cell, phone.speed / phone.cell, 1e-4f)
    }

    @Test
    fun `zoomed out, the land stays within its quad budget, zoomed in, bigger cells`() {
        for (zoom in floatArrayOf(0.5f, 0.67f, 1f, 1.5f, 2f)) {
            val v = HorizonView(1080f, 2340f, 2.8125f * zoom, zoom)
            val rows = Math.round(p.DEPTH / v.cell)
            val quads = rows.toLong() * (2 * ceil(v.halfWidth / v.cell).toLong())
            println("zoom $zoom: cell ${v.cell}, half width ${v.halfWidth}, quads $quads")
            assertTrue("zoom $zoom: $quads quads", quads <= p.QUAD_BUDGET)
        }
        assertTrue(HorizonView(1080f, 2340f, 5.6f, 2f).cell > phone.cell)
    }

    @Test
    fun `colours from SYNTH - a pink glow, teal edges, a dark sky`() {
        val t = HorizonTones(Palettes.SYNTH.colors)
        assertTrue(t.glow[0] > t.glow[2])
        assertTrue(t.line[1] > t.line[0] && t.line[2] > t.line[0])
        assertTrue(t.skyLow.max() < t.glow.max())
    }
}
