/*
 * ImpactTest: MEASURE IMPACT's arithmetic: battery readings in either unit,
 * a mean that ignores spikes, and a report measured against STILL.
 */
package com.thedungeons2077.fluidmotion.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactTest {
    @Test
    fun `readings in µA or mA, either sign, come out as mA`() {
        assertEquals(612f, Impact.milliamps(-612_000)!!, 1e-3f)
        assertEquals(612f, Impact.milliamps(612_000)!!, 1e-3f)
        assertEquals(612f, Impact.milliamps(612)!!, 1e-3f)
        assertEquals(612f, Impact.milliamps(-612)!!, 1e-3f)
        assertNull(Impact.milliamps(0))
        assertNull(Impact.milliamps(Int.MIN_VALUE))
    }

    @Test
    fun `the mean leaves the spikes out`() {
        val v = floatArrayOf(500f, 510f, 505f, 495f, 2000f, 500f, 505f, 495f, 510f, 10f)
        assertEquals(502.5f, Impact.trimmedMean(v, v.size), 1e-3f)
        assertTrue(Impact.trimmedMean(v, 0).isNaN())
    }

    @Test
    fun `capacity and cost an hour`() {
        assertEquals(5000f, Impact.capacityMah(2_500_000, 50)!!, 1e-2f)
        assertNull(Impact.capacityMah(0, 50))
        assertEquals(1f, Impact.percentPerHour(50f, 5000f), 1e-4f)
    }

    @Test
    fun `the report measures each rate against STILL`() {
        val r = Impact.report(
            listOf(
                Impact.Result("STILL", 0, 600f, 0f, 0f, Float.NaN, 30f),
                Impact.Result("BALANCED", 30, 640f, 29.8f, 0.61f, 1.2f, 30.5f),
            ),
            5000f,
            "S25",
        )
        assertTrue(r, "STILL: 600 mA" in r)
        assertTrue(r, "BALANCED 30: +40 mA (+0.8% an hour)" in r)
        assertTrue(r, "GPU 1.20 ms" in r)
        assertEquals(listOf("STILL", "ECO", "BALANCED", "SMOOTH"), Impact.STEPS.map { it.label })
    }
}
