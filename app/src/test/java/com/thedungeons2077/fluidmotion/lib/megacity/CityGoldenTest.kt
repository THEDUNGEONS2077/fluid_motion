/*
 * CityGoldenTest: MEGACITY's city is cyberdeck_terminal's, rule for rule.
 * Given the same random numbers, src/wallpaper/megacity/city.ts (0.10.0)
 * lays out the cities below; the port must lay out the same ones, tower for
 * tower, sign for sign, beacon for beacon. A city is compared by its count
 * of towers per layer, signs and beacons, and a CRC32 of a canonical text
 * of it (each tower's place, setback, spire and seed; each sign's; each
 * beacon's, its fractions to four places).
 *
 * Made by running city.ts under Node (type stripping; its imports given a
 * .ts) with a JavaScript copy of lib/rain/Rng (xorshift32: its 24-bit
 * fractions are exact in both) and node:zlib crc32. The port lays out in
 * Double, as JavaScript does, so they agree exactly. A layout rule changed
 * on purpose changes on both sides: regenerate.
 */
package com.thedungeons2077.fluidmotion.lib.megacity

import com.thedungeons2077.fluidmotion.lib.rain.Rng
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.CRC32

class CityGoldenTest {
    /** A city city.ts lays out: the screen (dp), the seed, towers per layer, signs, beacons, the CRC32. */
    private class Golden(val w: Double, val h: Double, val seed: Int, val towers: String, val signs: Int, val beacons: Int, val crc: Long)

    private val goldens = listOf(
        Golden(384.0, 832.0, 1, "15/11/6/6/3", 3, 2, 0x53005008L),
        Golden(384.0, 832.0, 2, "16/10/8/6/2", 3, 3, 0x62e78e6fL),
        Golden(384.0, 832.0, 3, "14/8/4/3/3", 3, 2, 0x07e81fa8L),
        Golden(360.0, 640.0, 4, "13/7/7/4/2", 3, 1, 0x5c3b6717L),
        Golden(411.4, 914.3, 5, "17/11/8/4/3", 4, 2, 0xa95729bbL),
        Golden(1920.0, 1080.0, 6, "70/46/33/20/9", 25, 11, 0xfa59dffdL),
        Golden(1280.0, 800.0, 7, "46/29/21/10/2", 10, 7, 0x478ace26L),
        Golden(2560.0, 1440.0, 8, "95/62/46/27/10", 38, 16, 0xec698ba5L),
        Golden(192.0, 416.0, 9, "8/7/4/2/1", 1, 0, 0x8a14108aL),
        Golden(768.0, 1664.0, 10, "29/23/15/9/4", 13, 3, 0x85874502L),
    )

    /** The city as the fixture's text (web.mjs's lines). */
    private fun text(c: City): String {
        val lines = ArrayList<String>()
        c.layers.forEachIndexed { l, ts ->
            for (t in ts) {
                val upper = t.upper?.let { "${it.x},${it.w},${it.top}" } ?: "-"
                val spire = if (t.hasSpire) "${t.spireX},${t.spireTop}" else "-"
                lines += "t$l:${t.x},${t.w},${t.top},$upper,$spire,${t.seed}"
            }
        }
        for (s in c.signs) lines += "s:${s.x},${s.y},${s.w},${s.h},${s.layer},${if (s.cool) 1 else 0},${if (s.vertical) 1 else 0},${s.seed}"
        for (b in c.beacons) lines += "b:${Math.round(b.x * 10)},${Math.round(b.y)},${b.layer},${Math.round(b.period * 1e4)},${Math.round(b.phase * 1e4)}"
        return lines.joinToString("\n")
    }

    @Test
    fun `the same random numbers lay out the web's cities`() {
        for (g in goldens) {
            val city = CityLayout.build(g.w, g.h, Rng(g.seed))
            val what = "${g.w} × ${g.h}, seed ${g.seed}"
            assertEquals("towers, $what", g.towers, city.layers.joinToString("/") { it.size.toString() })
            assertEquals("signs, $what", g.signs, city.signs.size)
            assertEquals("beacons, $what", g.beacons, city.beacons.size)
            assertEquals("layout, $what", g.crc, CRC32().apply { update(text(city).toByteArray(Charsets.UTF_8)) }.value)
        }
    }
}
