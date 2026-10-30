/*
 * KernelTest: QUANTUM KERNEL's code and motion on the phone (lib/kernel):
 * the rain's glyphs only, laid out as code within each depth's loop, as
 * many lanes as fit at the dashboard's lane width, the loops scrolling at
 * each depth's pace and wrapping, a line run now and then only while on
 * screen; the depths the dashboard's (0.27.1), the focus one where
 * kernel.frag expects it; and its colours from the palette, on black.
 */
package com.thedungeons2077.fluidmotion.lib.kernel

import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.rain.BASE_GLYPHS
import com.thedungeons2077.fluidmotion.lib.rain.KATAKANA
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelTest {
    /** The S25 Ultra: 1080 × 2340 px at 2.8125 px per dp. */
    private val view = KernelView(1080f, 2340f, 2.8125f)
    private val letters = KernelCode.letters(true)

    @Test
    fun `the depths are the dashboard's, and the focus one is fourth, where kernel frag reads it`() {
        assertEquals(listOf(9f, 11f, 15f, 24f, 38f), KernelParams.DEPTHS.map { it.font })
        assertEquals(listOf(6f, 10f, 16f, 26f, 42f), KernelParams.DEPTHS.map { it.speed })
        assertEquals(3, KernelParams.FOCUS_DEPTH)
    }

    @Test
    fun `code is written only in the rain's glyphs, within its loop`() {
        val allowed = (KATAKANA + BASE_GLYPHS + " ").toSet()
        for (d in KernelParams.DEPTHS.indices) {
            val lines = KernelCode.write(Rng(d + 1), view.cols[d], view.rows[d], view.lanes[d], KernelParams.DEPTHS[d], letters)
            assertTrue(lines.size > 3)
            for (l in lines) {
                assertTrue(l.row in 0 until view.rows[d])
                assertTrue(l.col >= 0)
                for (ch in l.text) assertTrue("'$ch' isn't the rain's", ch in allowed)
            }
        }
    }

    @Test
    fun `a phone's text is much smaller (the user, 0_13_1), a dashboard-wide screen's as the dashboard's`() {
        // 384 dp across: (384 / 1920)^0.57 ≈ 0.40.
        assertEquals(0.40f, view.textScale, 0.01f)
        assertEquals(2.8125f * view.textScale, view.unit, 1e-4f)
        assertEquals(1f, KernelView.textScaleFor(1920f), 1e-6f)
        assertEquals(1f, KernelView.textScaleFor(2560f), 1e-6f)
        assertEquals(KernelParams.TEXT_MIN, KernelView.textScaleFor(100f), 1e-6f)
        // About 60 glyphs across the code in focus (it was 27 at the dashboard's sizes).
        val f = KernelParams.FOCUS_DEPTH
        assertTrue("${view.cols[f]} across", view.cols[f] in 55..70)
    }

    @Test
    fun `a phone gets as many lanes as fit at the dashboard's lane width at its text size, at least one`() {
        // 384 dp at a text scale of 0.40 lays out as if 961 wide: about half the dashboard's lanes, rounded up.
        for (d in KernelParams.DEPTHS.indices) {
            val want = maxOf(1, kotlin.math.ceil(1080f / view.unit * KernelParams.DEPTHS[d].lanes / 1920f).toInt())
            assertEquals(want, view.lanes[d])
            assertTrue(view.lanes[d] >= (KernelParams.DEPTHS[d].lanes + 1) / 2)
        }
        // Each loop at least the screen's height.
        for (d in KernelParams.DEPTHS.indices) assertTrue(view.loopH[d] >= 2340f)
    }

    @Test
    fun `code spreads evenly across the width, no empty strip down either side (code ts 0_27_2)`() {
        for (d in KernelParams.DEPTHS.indices) {
            val cols = view.cols[d]
            var left = 0
            var right = 0
            for (k in 1..40) {
                for (l in KernelCode.write(Rng(k * 7919), cols, 200, view.lanes[d], KernelParams.DEPTHS[d], letters)) {
                    for (i in l.text.indices) {
                        val c = l.col + i
                        if (l.text[i] == ' ' || c >= cols) continue
                        if (c < cols / 3f) left++ else if (c >= 2f * cols / 3f) right++
                    }
                }
            }
            assertTrue("depth $d: left $left, right $right", minOf(left, right).toFloat() / maxOf(left, right) > 0.75f)
        }
    }

    @Test
    fun `the loops scroll at their own pace and wrap, and a line is run only while on screen`() {
        val f = KernelParams.FOCUS_DEPTH
        val focus = KernelCode.write(Rng(3), view.cols[f], view.rows[f], view.lanes[f], KernelParams.DEPTHS[f], letters)
        val m = KernelMotion(view, focus, Rng(4))
        var ran = 0
        repeat(30 * 60) {
            m.advance(1f / 30f)
            for (d in KernelParams.DEPTHS.indices) assertTrue(m.offset[d] >= 0f && m.offset[d] < view.loopH[d])
            if (m.runLine >= 0 && m.runT == 1f / 30f) {
                ran++
                val y = m.screenY(f, focus[m.runLine].row)
                // Picked on screen a frame ago (it has scrolled up one frame's step since: about 1 px at the phone's text scale).
                assertTrue("run at $y", y > 2340f * 0.05f - 5f && y < 2340f * 0.9f)
            }
        }
        // About once every 1.4 + 1.6 s over a minute.
        assertTrue("ran $ran", ran in 10..40)
    }

    @Test
    fun `colours from the palette, on black, the code blue in AZURE`() {
        val t = KernelTones(Palettes.AZURE.colors)
        assertTrue(t.back.all { it == 0f })
        val focus = t.tones[KernelParams.FOCUS]
        assertTrue(focus[2] > focus[0] * 2f)
    }
}
