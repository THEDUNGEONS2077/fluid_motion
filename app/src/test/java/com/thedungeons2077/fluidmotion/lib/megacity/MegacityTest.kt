/*
 * MegacityTest: MEGACITY's city (the same for a seed, its far layer nearly
 * a wall), signs and beacons only where they can be seen, the skyline
 * packed as the shader reads it, the figure on her ledge and her mesh, the
 * palette's tones, and the motion within its pools (the web's
 * appearance.test.ts megacity checks, here).
 */
package com.thedungeons2077.fluidmotion.lib.megacity

import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MegacityTest {
    /** Screens (dp): an S25 Ultra at FHD+, a small old phone, a tablet in landscape, and one zoomed to 200%. */
    private val screens = listOf(384.0 to 832.0, 360.0 to 640.0, 1280.0 to 800.0, 192.0 to 416.0)

    @Test
    fun `the same seed lays out the same city, its far layer nearly a wall`() {
        for ((w, h) in screens) {
            val a = CityLayout.build(w, h, Rng(7))
            val b = CityLayout.build(w, h, Rng(7))
            assertEquals(a.layers.map { l -> l.map { it.x to it.top } }, b.layers.map { l -> l.map { it.x to it.top } })
            assertEquals(MegacityParams.LAYERS.size, a.layers.size)
            val cols = w.toInt()
            val covered = (0 until cols).count { a.tops[0][it] < CityLayout.NO_TOWER }
            assertTrue("far layer covers $covered of $cols", covered > cols * 0.85)
        }
    }

    @Test
    fun `signs and beacons are only where they can be seen, signs on a tower and apart`() {
        for ((w, h) in screens) {
            val c = CityLayout.build(w, h, Rng(11))
            for (s in c.signs) {
                assertTrue(s.x >= 0 && s.x + s.w <= w)
                assertTrue(CityLayout.inSight(c.tops, c.cols, s.layer, s.x - 1.0, s.x + s.w + 1.0, s.y + s.h + 1.0))
                for (col in s.x until s.x + s.w) assertTrue(c.tops[s.layer][col] <= s.y)
            }
            for (i in c.signs.indices) for (j in i + 1 until c.signs.size) {
                val s = c.signs[i]
                val o = c.signs[j]
                assertFalse(s.x < o.x + o.w && o.x < s.x + s.w && s.y < o.y + o.h && o.y < s.y + s.h)
            }
            for (b in c.beacons) assertTrue(CityLayout.inSight(c.tops, c.cols, b.layer, b.x - 2, b.x + 2, b.y - 1))
        }
    }

    @Test
    fun `the skyline holds each layer's tops, spires marked`() {
        val c = CityLayout.build(1280.0, 800.0, Rng(3))
        val data = CityLayout.skyline(c)
        val w = MegacityParams.SKYLINE_WIDTH
        assertEquals(w * c.rows * c.layers.size * 4, data.size)
        for (l in c.layers.indices) for (col in listOf(0, 517, 1279)) assertEquals(c.tops[l][col], data[((l * c.rows + col / w) * w + col % w) * 4], 0f)
        for ((l, towers) in c.layers.withIndex()) for (t in towers) if (t.hasSpire && t.spireX in 0 until c.cols) {
            val i = ((l * c.rows + t.spireX / w) * w + t.spireX % w) * 4
            if (data[i] == t.spireTop.toFloat()) assertTrue(data[i + 3] < 0f)
        }
    }

    @Test
    fun `the figure stands on her ledge on the screen left of the middle, and her mesh is sound`() {
        for ((w, h) in screens) {
            val f = CityLayout.placeFigure(w, h)
            assertTrue(f.feet - f.height > 0 && f.feet < h && f.x < w / 2)
            assertTrue(f.ledgeRight > f.x && f.railRight < f.x)
        }
        val v = Figure.VERTICES
        val count = v.size / 2
        assertTrue(Figure.TRIANGLES.all { it in 0 until count })
        assertEquals(count, (0 until Figure.OUTLINES.size / 2).sumOf { Figure.OUTLINES[2 * it + 1] })
        var area = 0.0
        for (i in Figure.TRIANGLES.indices step 3) {
            val a = Figure.TRIANGLES[i]
            val b = Figure.TRIANGLES[i + 1]
            val c = Figure.TRIANGLES[i + 2]
            area += abs((v[2 * b] - v[2 * a]) * (v[2 * c + 1] - v[2 * a + 1]) - (v[2 * c] - v[2 * a]) * (v[2 * b + 1] - v[2 * a + 1])) / 2.0
        }
        assertTrue("figure area $area", area in 0.1..0.25)
        val fg = CityLayout.foreground(CityLayout.build(384.0, 832.0, Rng(5)), CityLayout.tones(Palettes.NEON.colors), 2.8125f)
        assertEquals(0, fg.size % (CityLayout.SHAPE * 3))
        for (i in 5 until fg.size step CityLayout.SHAPE) assertTrue(fg[i] in 0f..1f)
    }

    @Test
    fun `colours come from the palette, the haze the cool hue and the signs the accent`() {
        val c = Palettes.NEON.colors
        val t = CityLayout.tones(c)
        assertEquals(((c.cool shr 16) and 255) / 255f, t.skyTop[0], 1e-6f)
        assertEquals((c.accent and 255) / 255f, t.signWarm[2], 1e-6f)
        assertTrue(t.skyLow[0] > t.skyTop[0] && t.skyLow[2] > t.skyLow[1])
        assertTrue(t.figure[2] > t.figure[0])
    }

    @Test
    fun `the motion stays in its pools, and each frame packs within its room`() {
        for ((w, h) in screens) {
            val c = CityLayout.build(w, h, Rng(9))
            val sim = CitySim(c, Rng(10))
            val tones = CityLayout.tones(Palettes.NEON.colors)
            val signs = FloatArray(CityFrame.signRoom(c))
            val glows = FloatArray(CityFrame.glowRoom(c))
            val scratch = FloatArray(3)
            var flickered = c.signs.isEmpty()
            repeat(30 * 120) {
                sim.advance(1f / 30f)
                assertTrue(sim.drones in 1..MegacityParams.DRONES_MAX)
                for (b in sim.signBright) {
                    assertTrue(b in 0f..1f)
                    if (b < 0.5f) flickered = true
                }
                for (b in c.beacons.indices) assertTrue(sim.beacon(b) in 0f..1f)
                assertEquals(c.signs.size, CityFrame.signs(sim, tones, GlowLevel.HIGH, signs, scratch))
                assertTrue(CityFrame.glows(sim, tones, GlowLevel.HIGH, glows, scratch) * CityFrame.GLOW <= glows.size)
                assertTrue(sim.time in 0f..MegacityParams.TIME_WRAP)
            }
            assertTrue("a sign flickered", flickered)
        }
    }
}
