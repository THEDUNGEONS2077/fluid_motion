/*
 * QualityTest: QUALITY's resolution caps, and AUTO CALIBRATE's choice of
 * quality and rate from measured frame costs.
 */
package com.thedungeons2077.fluidmotion.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QualityTest {
    private val s25 = 2.8125f

    @Test
    fun `each quality caps the resolution, ULTRA never`() {
        assertEquals(1.5f / s25, Quality.LOW.scale(s25), 1e-6f)
        assertEquals(0.8f, Quality.MEDIUM.scale(s25), 1e-6f)
        assertEquals(1f, Quality.HIGH.scale(s25), 0f)
        assertEquals(1f, Quality.ULTRA.scale(3.75f), 0f)
        assertEquals(0.8f, Quality.HIGH.scale(3.75f), 1e-6f)
        // A low-density screen is drawn at full resolution from MEDIUM up.
        assertEquals(1f, Quality.MEDIUM.scale(2f), 0f)
        assertEquals(Quality.HIGH, Quality.find("nonsense"))
    }

    @Test
    fun `the highest quality within budget is chosen`() {
        val samples = listOf(
            Calibration.Sample(Quality.LOW, 0.6f, 0.9f),
            Calibration.Sample(Quality.MEDIUM, 1.1f, 0.9f),
            Calibration.Sample(Quality.HIGH, 2.4f, 1f),
            Calibration.Sample(Quality.ULTRA, 4.2f, 1f),
        )
        assertEquals(Quality.HIGH, Calibration.choose(samples))
        // An unmeasurable GPU counts as within; the CPU still must be.
        assertEquals(Quality.ULTRA, Calibration.choose(listOf(Calibration.Sample(Quality.ULTRA, Float.NaN, 1f))))
        assertEquals(Quality.LOW, Calibration.choose(listOf(Calibration.Sample(Quality.LOW, Float.NaN, 1f), Calibration.Sample(Quality.HIGH, Float.NaN, 4f))))
    }

    @Test
    fun `a weak phone gets the cheapest quality and the ECO rate`() {
        val samples = listOf(
            Calibration.Sample(Quality.LOW, 3.5f, 1f),
            Calibration.Sample(Quality.MEDIUM, 5f, 1f),
            Calibration.Sample(Quality.HIGH, 7f, 1f),
        )
        val chosen = Calibration.choose(samples)
        assertEquals(Quality.LOW, chosen)
        assertEquals(PowerProfile.ECO, Calibration.profile(samples.first { it.quality == chosen }))
        assertEquals(PowerProfile.BALANCED, Calibration.profile(Calibration.Sample(Quality.ULTRA, 1.2f, 0.9f)))
    }

    @Test
    fun `qualities that draw the same are measured once`() {
        // FHD+ S25 Ultra: HIGH and ULTRA are both full resolution.
        assertEquals(Calibration.key(Quality.HIGH, s25, false), Calibration.key(Quality.ULTRA, s25, false))
        assertNotEquals(Calibration.key(Quality.MEDIUM, s25, false), Calibration.key(Quality.HIGH, s25, false))
        // On a 2 px/dp screen LOW is scaled, MEDIUM is full; for the neural net the link glow tells LOW apart too.
        assertEquals(Calibration.key(Quality.MEDIUM, 2f, true), Calibration.key(Quality.HIGH, 2f, true))
        assertNotEquals(Calibration.key(Quality.LOW, 1.5f, true), Calibration.key(Quality.MEDIUM, 1.5f, true))
    }
}
