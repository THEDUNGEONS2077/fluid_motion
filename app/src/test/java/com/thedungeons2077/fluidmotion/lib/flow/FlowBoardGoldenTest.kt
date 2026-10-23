/*
 * FlowBoardGoldenTest: the board is cyberdeck_terminal's, rule for rule.
 * Given the same random numbers, src/wallpaper/data-flow/board.ts (0.9.0)
 * lays out the boards below; the port must lay out the same ones, chip
 * for chip and trace for trace. A board is compared by a CRC32 of its
 * layout (each chip's place, size and pins; each trace's points, hue,
 * ends, chip and bus) and the sum of its traces' widths (floats here,
 * doubles there: equal to a hair).
 *
 * Made by running board.ts under Node with a JavaScript copy of
 * lib/rain/Rng (xorshift32: its 24-bit fractions are exact in both) and
 * the same text and checksum (node:zlib crc32). 80 boards (4 sizes × 20
 * seeds) were compared in full when the port was made; these few keep it
 * so. A board rule changed on purpose changes on both sides: regenerate.
 */
package com.thedungeons2077.fluidmotion.lib.flow

import com.thedungeons2077.fluidmotion.lib.rain.Rng
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.CRC32

class FlowBoardGoldenTest {
    /** A board board.ts lays out: the screen (dp), the seed, its traces, its layout's CRC32, its widths' sum. */
    private class Golden(val w: Float, val h: Float, val seed: Int, val traces: Int, val crc: Long, val widths: Float)

    private val goldens = listOf(
        Golden(384f, 832f, 1, 27, 0x052be764L, 31.3219f),
        Golden(384f, 832f, 2, 28, 0xacb56a49L, 36.4999f),
        Golden(360f, 640f, 3, 30, 0x064f1968L, 37.6689f),
        Golden(1280f, 800f, 4, 47, 0x50ff9f09L, 55.7085f),
        Golden(1920f, 1080f, 5, 72, 0x855b7bb3L, 86.1642f),
    )

    /** The board as the fixture's text: a line per chip, then per trace (ends named as the web names them). */
    private fun text(b: FlowBoard): String = (
        b.chips.map { "c:${it.x},${it.y},${it.w},${it.h},${if (it.main) 1 else 0},${it.pins.size}" } +
            b.traces.map { t ->
                "t:${t.pts.joinToString(",")}|${if (t.cool) 1 else 0}|${t.start.name.lowercase()}|${t.end.name.lowercase()}|${t.chip}|${t.bus}"
            }
        ).joinToString("\n")

    @Test
    fun `the same random numbers lay out the web's boards`() {
        for (g in goldens) {
            val b = FlowBoard.build(g.w, g.h, Rng(g.seed))
            val what = "${g.w} × ${g.h}, seed ${g.seed}"
            assertEquals("traces, $what", g.traces, b.traces.size)
            val crc = CRC32().apply { update(text(b).toByteArray(Charsets.UTF_8)) }.value
            assertEquals("layout, $what", g.crc, crc)
            assertEquals("widths, $what", g.widths, b.traces.sumOf { it.width.toDouble() }.toFloat(), 1e-3f)
        }
    }
}
