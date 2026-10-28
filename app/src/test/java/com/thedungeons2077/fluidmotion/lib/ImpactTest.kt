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
        assertEquals(listOf("STILL", "ECO", "BALANCED", "SMOOTH", "STILL AGAIN"), Impact.steps(emptyList()).map { it.label })
        // On the S25 Ultra's display, ECO is measured at the rate it really draws at.
        assertEquals(listOf(0, 24, 30, 60, 0), Impact.steps(listOf(10f, 24f, 30f, 48f, 60f)).map { it.fps })
    }

    @Test
    fun `STILL at both ends, the base their mean, and a run where a slower rate reads dearer is flagged`() {
        val results = listOf(
            Impact.Result("STILL", 0, 120f, 0f, 0f, Float.NaN, 26f),
            Impact.Result("ECO", 24, 271f, 24f, 0.77f, 2.11f, 26f),
            Impact.Result("BALANCED", 30, 161f, 30f, 0.82f, 2.08f, 26f),
            Impact.Result("SMOOTH", 60, 199f, 60f, 0.78f, 2.12f, 26f),
            Impact.Result("STILL AGAIN", 0, 124f, 0f, 0f, Float.NaN, 26f),
        )
        assertEquals(122f, Impact.base(results), 1e-4f)
        assertEquals(listOf("ECO > BALANCED", "ECO > SMOOTH"), Impact.inconsistent(results))
        val r = Impact.report(results, 5000f, "S25")
        assertTrue(r, "STILL: 120 mA (the base: screen and phone)" in r)
        assertTrue(r, "STILL AGAIN: 124 mA (+4 mA over the run; the base is their mean, 122 mA)" in r)
        assertTrue(r, "BALANCED 30: +39 mA" in r)
        assertTrue(r, "NOISY: ECO > BALANCED, ECO > SMOOTH" in r)
        // A steady run isn't flagged.
        assertTrue(Impact.inconsistent(results.filter { it.label != "ECO" }).isEmpty())
    }
}
