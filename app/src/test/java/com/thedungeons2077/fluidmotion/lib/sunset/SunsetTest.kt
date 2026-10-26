/*
 * SunsetTest: SUNSET's framing and colours (lib/sunset): on 16:9 the web's
 * own numbers; on an upright phone the sun the same share of the short
 * side, its base cut by the horizon as in the reference, the grid bigger
 * (the web's squares across ÷ √ how much narrower), the same shape, at the
 * same pace on screen; the grid's distance wrapping where the squares and waves
 * repeat; the colours from OUTRUN. The shaders are drawn by
 * tools/shader-check from FrameDumpTest's dump.
 */
package com.thedungeons2077.fluidmotion.lib.sunset

import com.thedungeons2077.fluidmotion.lib.Palettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SunsetTest {
    private val p = SunsetParams
    private val wide = SunsetView(1920f, 1080f, 1f, 1f)
    private val phone = SunsetView(1080f, 2340f, 2.8125f, 1f)

    /** Squares across the bottom edge: (width / 2) × camera height / (px below the horizon there), both sides. */
    private fun bottomSquares(v: SunsetView) = v.width * v.cameraHeight / (v.height - v.horizon)

    /** A square's height over its width at the bottom edge: (px below the horizon) / focal. */
    private fun bottomShape(v: SunsetView) = (v.height - v.horizon) / v.focal

    /** How fast the bottom edge's ground moves, in shares of the ground's height a second. */
    private fun pace(v: SunsetView): Float {
        val dy = v.height - v.horizon
        return dy * dy / (v.focal * v.cameraHeight) * v.speed / dy
    }

    @Test
    fun `on 16 to 9 it's the web's framing`() {
        assertEquals(1f, wide.narrow, 1e-5f)
        assertEquals(1080f, wide.big, 1e-3f)
        assertEquals(1080f * p.SUN_Y, wide.sunY, 1e-2f)
        assertEquals(1080f, wide.focal, 1e-3f)
        assertEquals(p.CAMERA_HEIGHT, wide.cameraHeight, 1e-5f)
        assertEquals(p.SPEED, wide.speed, 1e-5f)
        assertEquals(p.MELT * 1080f, wide.melt, 1e-2f)
    }

    @Test
    fun `the melt reaches as far as the grid's lines crowd - as on the web, in the lines' own px`() {
        // Lines crowd where a pixel spans a set share of a row: focal × camera height / dy² is the same on both.
        fun crowdAt(v: SunsetView) = v.melt * v.melt / (v.focal * v.cameraHeight)
        assertEquals(crowdAt(wide), crowdAt(phone), 0.01f * crowdAt(wide))
    }

    @Test
    fun `on an upright phone the sun sits on the horizon, its base cut, sized by the short side`() {
        assertEquals(1080f, phone.big, 1e-3f)
        // As far above the horizon, in the sun's own radii, as on the web.
        assertEquals((wide.horizon - wide.sunY) / (p.SUN_R * wide.big), (phone.horizon - phone.sunY) / (p.SUN_R * phone.big), 1e-4f)
        assertTrue("the horizon cuts the sun's base", phone.sunY + p.SUN_R * phone.big > phone.horizon)
        assertTrue("the sun's top is on screen", phone.sunY - p.SUN_R * phone.big > 0f)
    }

    @Test
    fun `on an upright phone the grid is the web's, bigger - squares across, their shape, their pace`() {
        assertEquals(bottomSquares(wide) / kotlin.math.sqrt(phone.narrow), bottomSquares(phone), 0.01f)
        assertEquals(bottomShape(wide), bottomShape(phone), 1e-4f)
        assertEquals(pace(wide), pace(phone), 1e-4f)
    }

    @Test
    fun `the grid's distance wraps where squares and waves repeat`() {
        var t = 0f
        repeat(10_000) { t = advanceTravel(t, 1f / 30f, phone.speed) }
        assertTrue(t >= 0f && t < p.WRAP)
        // Whole squares (WRAP is whole) and whole waves (whole cycles over WRAP).
        assertEquals(0f, p.WRAP % 1f, 0f)
        assertEquals(p.WAVE_ACROSS * 2 * Math.PI, (p.WAVE_K * p.WAVE_ACROSS * p.WRAP).toDouble(), 1e-3)
        // A long stall moves it on by at most a tenth of a second's worth.
        assertEquals(p.MAX_FRAME_STEP * phone.speed, advanceTravel(0f, 5f, phone.speed), 1e-5f)
    }

    @Test
    fun `colours from OUTRUN - a golden sun top, a pink base, purple sky left, blue right`() {
        val t = SunsetTones(Palettes.OUTRUN.colors)
        assertTrue(t.sunTop[0] > 0.9f && t.sunTop[1] > 0.7f && t.sunTop[2] < 0.5f)
        assertTrue(t.sunBase[0] > t.sunBase[2])
        assertTrue(t.skyRight[2] > t.skyRight[0])
        assertTrue(t.skyLeft[0] > 0.05f && t.skyLeft[2] > 0.05f)
        assertTrue(t.ground.max() < 0.15f)
    }
}
