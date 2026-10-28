/*
 * CalibrationSummaryTest: AUTO CALIBRATE's summary and recommended MANUAL
 * settings (lib/CalibrationSummary.kt), from the S25 Ultra's real
 * calibration (fluid_motion 0.10.0, 2026-10-28: PRISM over budget even at
 * LOW) and a weak phone's made-up one; and its samples kept as text.
 */
package com.thedungeons2077.fluidmotion.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationSummaryTest {
    /** A wallpaper measured at LOW, MEDIUM, HIGH and ULTRA (GPU ms; CPU 0.9 ms). */
    private fun m(id: String, vararg gpu: Float) = CalibrationSummary.Measured(id, id.uppercase(), Quality.entries.mapIndexed { i, q -> Calibration.Sample(q, gpu[i], 0.9f) })

    /** The S25 Ultra, 0.10.0 (HIGH and ULTRA are the same there). */
    private val s25 = listOf(
        m("matrix-rain", 0.86f, 1.07f, 1.32f, 1.32f),
        m("neural-net", 0.65f, 1.10f, 1.10f, 1.10f),
        m("velocity", 0.72f, 1.15f, 0.73f, 0.73f),
        m("data-flow", 0.46f, 0.94f, 0.92f, 0.92f),
        m("megacity", 0.60f, 0.92f, 0.85f, 0.85f),
        m("horizon", 0.99f, 1.76f, 2.15f, 2.15f),
        m("sunset", 0.73f, 1.18f, 1.28f, 1.28f),
        m("prism", 3.72f, 7.85f, 11.76f, 11.76f),
    )
    private val rates = listOf(10f, 24f, 30f, 48f, 60f)
    private val model = CostModel(mapOf("prism@24" to 53f, "prism@30" to 60f, "prism@60" to 153f, "sunset@30" to 44f, "sunset@60" to 87f))

    @Test
    fun `the S25 Ultra, AUTO puts only PRISM at ECO and MANUAL is recommended at ULTRA with PRISM noted`() {
        val s = CalibrationSummary.of(s25, rates, model)
        assertEquals(listOf("PRISM"), s.heavy)
        val prism = s.picks.first { it.label == "PRISM" }
        assertEquals(Quality.LOW to PowerProfile.ECO, prism.quality to prism.profile)
        // Everything else: its sharpest, at BALANCED.
        assertTrue(s.picks.filter { it.label != "PRISM" }.all { it.quality == Quality.ULTRA && it.profile == PowerProfile.BALANCED })
        assertEquals(PowerProfile.BALANCED to Quality.ULTRA, s.recommended.profile to s.recommended.quality)
        assertEquals(listOf("PRISM"), s.recommended.over)
        assertEquals(30, s.recommended.fps)
        // The heaviest at 30 fps: PRISM's 11.76 ms, 35% of the time.
        assertEquals(11.76f * 30 / 1000, s.recommended.gpuBusy, 1e-4f)
    }

    @Test
    fun `the three presets, battery cheapest, smoothest dearest, and SMOOTHEST only as sharp as 60 fps holds`() {
        val s = CalibrationSummary.of(s25, rates, model)
        assertEquals(listOf("LONGEST BATTERY", "RECOMMENDED", "SMOOTHEST"), s.presets.map { it.name })
        val (battery, recommended, smooth) = s.presets
        assertEquals(PowerProfile.ECO to Quality.LOW, battery.profile to battery.quality)
        // ECO is the display's own slow rate.
        assertEquals(24, battery.fps)
        assertTrue(battery.meanMa < recommended.meanMa && recommended.meanMa < smooth.meanMa)
        // PRISM's ULTRA (11.76 ms) fits in three quarters of a 60 fps frame (12.5 ms): it held 60 fps on this phone.
        assertEquals(PowerProfile.SMOOTH to Quality.ULTRA, smooth.profile to smooth.quality)
        assertEquals(60, smooth.fps)
        // A phone where it's slower: SMOOTHEST steps down to what holds 60 fps.
        val slower = s25.map { if (it.id == "prism") m("prism", 4f, 9f, 14f, 14f) else it }
        assertEquals(Quality.MEDIUM, CalibrationSummary.of(slower, rates, model).presets[2].quality)
    }

    @Test
    fun `a weak phone where nothing fits, LOW everywhere and every wallpaper at ECO`() {
        val weak = listOf(m("matrix-rain", 3.5f, 5f, 7f, 7f), m("prism", 17f, 30f, 45f, 45f))
        val s = CalibrationSummary.of(weak, listOf(60f, 120f), CostModel(emptyMap()))
        assertTrue(s.picks.all { it.quality == Quality.LOW && it.profile == PowerProfile.ECO })
        assertEquals(Quality.LOW, s.recommended.quality)
        assertEquals(listOf("MATRIX-RAIN", "PRISM"), s.recommended.over)
        // ECO on a display without a slow rate: 20 fps.
        assertEquals(20, s.presets[0].fps)
        // A typical figure, 1.2 mA per frame a second, for each.
        assertEquals(1.2f * 30, s.recommended.meanMa, 1e-3f)
    }

    @Test
    fun `samples kept as text and read back, anything unreadable left out`() {
        val samples = mapOf("prism" to s25.last().samples, "sunset" to listOf(Calibration.Sample(Quality.LOW, Float.NaN, 0.7f)))
        val back = CalibrationSummary.decode(CalibrationSummary.encode(samples))
        assertEquals(listOf("prism", "sunset"), back.keys.toList())
        assertEquals(11.76f, back.getValue("prism")[3].gpuMs, 1e-3f)
        assertTrue(back.getValue("sunset")[0].gpuMs.isNaN())
        assertTrue(CalibrationSummary.decode("rubbish;prism:NOPE:1:1;x:LOW:a:1").isEmpty())
        assertEquals("A, B and C", CalibrationSummary.names(listOf("A", "B", "C")))
    }
}
