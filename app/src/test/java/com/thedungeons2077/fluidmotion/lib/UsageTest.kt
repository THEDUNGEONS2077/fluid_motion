/*
 * UsageTest: INSIGHT's log: time split at the hours it crosses, merged and
 * pruned, stored and read back, summed into days in the phone's time zone,
 * and its battery estimate from measured (or typical) figures.
 */
package com.thedungeons2077.fluidmotion.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageTest {
    private val zone = ZoneId.of("Africa/Johannesburg")
    private val hourMs = 3_600_000L

    /** Epoch ms of a local time in [zone]. */
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `time on screen is split at the hours it crosses`() {
        val hours = HashMap<Long, UsageHour>()
        Usage.addTime(hours, "matrix-rain@30", at(2026, 10, 24, 9, 40), at(2026, 10, 24, 11, 10))
        val sorted = hours.values.sortedBy { it.hour }
        assertEquals(3, sorted.size)
        assertEquals(listOf(20 * 60_000L, 60 * 60_000L, 10 * 60_000L), sorted.map { it.onScreen.getValue("matrix-rain@30") })
    }

    @Test
    fun `the log is merged, pruned, stored and read back`() {
        val a = UsageHour(100).apply { onScreen["velocity@30"] = 1000L; frames = 30; cpuNs = 30_000_000; gpuNs = 40_000_000; gpuFrames = 30 }
        val b = UsageHour(100).apply { onScreen["velocity@30"] = 500L; onScreen["velocity@0"] = 200L; frames = 15 }
        val old = UsageHour(10).apply { onScreen["velocity@30"] = 9L }
        val merged = Usage.merge(listOf(old, a), listOf(b), keepFrom = 50)
        assertEquals(1, merged.size)
        val h = merged[0]
        assertEquals(1500L, h.onScreen["velocity@30"])
        assertEquals(200L, h.onScreen["velocity@0"])
        assertEquals(45L, h.frames)
        val back = Usage.decode(Usage.encode(merged)).single()
        assertEquals(h.onScreen, back.onScreen)
        assertEquals(listOf(h.frames, h.cpuNs, h.gpuNs, h.gpuFrames), listOf(back.frames, back.cpuNs, back.gpuNs, back.gpuFrames))
        assertTrue(Usage.decode("rubbish\n1|2").isEmpty())
        assertFalse(UsageHour(5).apply { frames = 1 }.empty)
    }

    @Test
    fun `measured figures come first, then the same rate, then per frame, then typical`() {
        val model = CostModel(mapOf("matrix-rain@30" to 34f, "matrix-rain@60" to 78f, "velocity@24" to -2f))
        // Two of its own: their mean mA per frame a second, times the rate.
        assertEquals((34f / 30 + 78f / 60) / 2 * 30, model.milliamps("matrix-rain", 30), 1e-3f)
        assertEquals(34f, model.milliamps("neural-net", 30), 0f)
        // A small negative measurement (noise) counts as nothing.
        assertEquals(0f, model.milliamps("velocity", 24), 0f)
        assertEquals(0f, model.milliamps("matrix-rain", 0), 0f)
        // 15 fps on a wallpaper never measured: the measured mA per frame a second, averaged.
        val perFps = (34f / 30 + 78f / 60 + -2f / 24) / 3
        assertEquals(perFps * 15, model.milliamps("neural-net", 15), 1e-4f)
        assertEquals(36f, CostModel(emptyMap()).milliamps("matrix-rain", 30), 1e-4f)
        assertEquals(mapOf("a@30" to 1.5f), CostModel.decode(CostModel.encode(mapOf("a@30" to 1.5f))))
    }

    @Test
    fun `one bad reading among three doesn't move a wallpaper's figure`() {
        // The S25 Ultra's HORIZON, 0.10.0: ECO read +149 mA, BALANCED +39, SMOOTH +77.
        val model = CostModel(mapOf("horizon@24" to 149f, "horizon@30" to 39f, "horizon@60" to 77f))
        // Per frame a second: 6.21, 1.30, 1.28; the median is BALANCED's.
        val median = 39f / 30
        assertEquals(median * 24, model.milliamps("horizon", 24), 1e-3f)
        assertEquals(median * 30, model.milliamps("horizon", 30), 1e-3f)
        assertTrue(model.milliamps("horizon", 24) < model.milliamps("horizon", 30))
    }

    @Test
    fun `days are summed in the phone's time zone, with the battery they used`() {
        val hours = HashMap<Long, UsageHour>()
        // 23:30 to 00:30 local: half on each day.
        Usage.addTime(hours, "matrix-rain@30", at(2026, 10, 23, 23, 30), at(2026, 10, 24, 0, 30))
        Usage.addTime(hours, "matrix-rain@0", at(2026, 10, 24, 8, 0), at(2026, 10, 24, 8, 15))
        hours.getValue(Usage.hourOf(at(2026, 10, 24, 0, 10))).apply { frames = 54_000; cpuNs = 54_000_000_000; gpuNs = 67_500_000_000; gpuFrames = 54_000 }
        val model = CostModel(mapOf("matrix-rain@30" to 34f))
        val days = Usage.days(hours.values.toList(), model, zone, LocalDate.of(2026, 10, 24))
        assertEquals(7, days.size)
        assertEquals(LocalDate.of(2026, 10, 24), days.last().date)
        val yesterday = days[5]
        val today = days[6]
        assertEquals(30 * 60_000L, yesterday.drawingMs)
        assertEquals(30 * 60_000L, today.drawingMs)
        assertEquals(15 * 60_000L, today.stillMs)
        assertEquals(45 * 60_000L, today.onScreenMs)
        // Half an hour at +34 mA: 17 mAh; still time costs nothing extra.
        assertEquals(17f, today.mAh, 1e-3f)
        assertEquals(1f, today.cpuMs, 1e-4f)
        assertEquals(1.25f, today.gpuMs, 1e-4f)
        assertEquals("matrix-rain@30", today.breakdown.first().first)
        assertTrue(days[0].gpuMs.isNaN())
        assertEquals(0L, days[0].onScreenMs)
    }
}
