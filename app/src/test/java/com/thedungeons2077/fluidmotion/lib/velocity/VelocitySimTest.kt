/*
 * VelocitySimTest: VELOCITY's colours come from the palette as the web's
 * do; on a 16:9 landscape screen its geometry is the web's exactly; on a
 * portrait phone the panels keep the reference's slant and the flare's arc
 * runs off the screen at both ends; panels and streaks come round forever.
 */
package com.thedungeons2077.fluidmotion.lib.velocity

import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class VelocitySimTest {
    @Test
    fun `takes its light from the palette, as the web does`() {
        val t = velocityTones(Palettes.BLOOD.colors)
        assertEquals(0xff3040, t.red)
        // The web's tones() for BLOOD: pale 170, 198, 246; flare the cool hue lifted 0.35.
        assertEquals(0xaac6f6, t.pale)
        assertEquals(0x6d9db9, t.flare)
    }

    @Test
    fun `on a 16 by 9 screen the flare is the web's`() {
        val s = VelocitySim(1920f, 1080f, Rng(1))
        assertEquals(0.995f * 1920f, s.flareX, 0.5f)
        assertEquals(0.88f * 1080f, s.flareY, 0.5f)
        assertEquals(0.17f * 1920f, s.flareR, 0.5f)
        assertEquals(0.72f * 1920f, s.arcX, 0.5f)
        assertEquals(0.5f * 1080f, s.arcY, 0.5f)
        assertEquals(0.36f * 1920f, s.arcR, 0.5f)
        // Leans are shares of the width there, as on the web.
        for (i in 0 until s.panels) {
            val kind = if (i == 0) VelocityParams.PALE else VelocityParams.RED
            assertTrue(s.lean[i] / 1920f in kind.lean.start - 1e-4f..kind.lean.endInclusive + 1e-4f)
        }
    }

    @Test
    fun `on a phone the panels slant as the reference and the arc runs off both edges`() {
        val w = 412f
        val h = 915f
        val s = VelocitySim(w, h, Rng(2))
        // The pale panel leans 11 to 16 percent of a 16:9 width at this height.
        val slant = s.lean[0] / (h * 16f / 9f)
        assertTrue("slant $slant", slant in 0.11f - 1e-4f..0.16f + 1e-4f)
        // Both ends of the arc are off the screen (no drawn-looking end mid-screen).
        for (a in listOf(VelocityParams.ARC_FROM, VelocityParams.ARC_TO)) {
            val x = s.arcX + s.arcR * cos(a)
            val y = s.arcY + s.arcR * sin(a)
            assertTrue("arc end ($x, $y) off screen", x > w || y > h)
        }
        // The bloom sits in the corner.
        assertTrue(s.flareX > 0.95f * w && s.flareY > 0.8f * h)
        // The red panels start spread over the width, one in each third (centres).
        for (i in 1 until s.panels) {
            val centre = s.px[i] + s.pw[i] / 2f
            assertTrue("red panel $i centre $centre", centre in w * (i - 1) / 3f..w * i / 3f)
        }
    }

    @Test
    fun `panels and streaks come round forever, in their pools`() {
        val s = VelocitySim(412f, 915f, Rng(3))
        var most = 0
        val wrapped = IntArray(s.panels)
        val last = s.px.copyOf()
        // An hour at 30 frames a second: every panel crosses many times (a new shape starts left of the old place).
        repeat(30 * 3600) {
            s.advance(1f / 30f)
            most = maxOf(most, s.streaks)
            for (i in 0 until s.panels) {
                if (s.px[i] < last[i]) wrapped[i]++
                last[i] = s.px[i]
            }
        }
        for (n in wrapped) assertTrue("panels came round: ${wrapped.toList()}", n >= 1)
        assertTrue("streaks: $most", most in 1 until VelocityParams.MAX_STREAKS)
        for (i in 0 until s.streaks) assertTrue(s.sx[i] - s.sl[i] < s.w)
    }
}
