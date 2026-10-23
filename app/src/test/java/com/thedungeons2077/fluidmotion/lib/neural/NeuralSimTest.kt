/*
 * NeuralSimTest: the net's links (each pair once, never too long, hubs
 * reaching many), its colours along the diagonal, and its motion (nodes stay
 * round their homes, lights stay in 0–1, signals stay in their pool).
 */
package com.thedungeons2077.fluidmotion.lib.neural

import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.mixRgb
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class NeuralSimTest {
    @Test
    fun `links each pair once, never longer than allowed, and lets a hub reach many`() {
        val rng = Rng(7)
        val n = 60
        val xs = FloatArray(n) { rng.next() * 1000f }
        val ys = FloatArray(n) { rng.next() * 600f }
        val want = IntArray(n) { if (it == 0) 9 else 3 }
        val links = linkNodes(xs, ys, want, 400f, Rng(3))
        val keys = HashSet<Long>()
        for (k in 0 until links.size / 2) {
            val a = links[2 * k]
            val b = links[2 * k + 1]
            assertTrue(a < b)
            assertTrue(keys.add(a.toLong() * n + b))
            assertTrue(hypot(xs[a] - xs[b], ys[a] - ys[b]) <= 400f)
        }
        var hub = 0
        for (k in 0 until links.size / 2) if (links[2 * k] == 0 || links[2 * k + 1] == 0) hub++
        assertTrue("hub links: $hub", hub >= 9)
    }

    @Test
    fun `shades from the cool colour at the top left to the accent at the bottom right`() {
        assertEquals(0f, diagonal(0f, 0f), 0f)
        assertEquals(1f, diagonal(1f, 1f), 0f)
        assertTrue(diagonal(0f, 1f) > diagonal(1f, 0f))
        val c = Palettes.BLOOD.colors
        assertEquals(c.accent, shade(c, 1f))
        // The top left is the cool hue (blue over red), lifted towards white.
        val top = shade(c, 0f)
        assertEquals(mixRgb(c.cool, 0xffffff, 0.4f), top)
        assertTrue((top and 255) > (top shr 16 and 255))
    }

    @Test
    fun `a phone screen gets a full net, and it moves within bounds`() {
        val s = NeuralSim(412f, 915f, Rng(11))
        assertTrue("nodes: ${s.n}", s.n >= 64)
        assertTrue("links: ${s.links.size / 2}", s.links.size / 2 >= s.n)
        var most = 0
        repeat(30 * 120) {
            s.advance(1f / 30f)
            most = maxOf(most, s.pulses)
            for (i in 0 until s.n) {
                assertTrue(s.flash[i] in 0f..1f)
                assertTrue("drift x", kotlin.math.abs(s.x[i] - s.hx[i]) <= 38f + 1e-3f)
                assertTrue("drift y", kotlin.math.abs(s.y[i] - s.hy[i]) <= 38f + 1e-3f)
            }
            for (i in 0 until s.nb) {
                assertTrue(s.bx[i] >= -s.br[i] && s.bx[i] <= s.w + s.br[i])
                assertTrue(s.by[i] >= -s.br[i] && s.by[i] <= s.h + s.br[i])
            }
            for (i in 0 until s.pulses) {
                assertTrue(s.pulseAlong[i] in 0f..1f)
                // A signal is on its link: between its two nodes.
                val x = s.pulseX(i)
                assertTrue(x.isFinite() && s.pulseY(i).isFinite())
            }
        }
        assertTrue("signals ran: $most", most > 0)
        assertTrue(most <= NeuralParams.MAX_PULSES)
    }
}
