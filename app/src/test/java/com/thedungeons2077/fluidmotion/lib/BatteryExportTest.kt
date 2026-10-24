/*
 * BatteryExportTest: COPY BATTERY DATA (lib/BatteryExport.kt): every
 * section in order, the hours in local time with their battery estimate
 * from the measured cost model, and plain fallbacks when nothing's there yet.
 */
package com.thedungeons2077.fluidmotion.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class BatteryExportTest {
    private val zone = ZoneId.of("Africa/Johannesburg")
    private val label = { id: String -> id.replace('-', ' ').uppercase() }

    /** The hour (hours since 1970) of a local time in [zone]. */
    private fun hourAt(y: Int, m: Int, d: Int, h: Int): Long = Usage.hourOf(ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli())

    @Test
    fun `every section in order, the hours in local time with their battery estimate`() {
        val h = UsageHour(hourAt(2026, 10, 24, 13)).apply {
            onScreen["megacity@30"] = 30 * 60_000L
            onScreen["megacity@0"] = 6 * 60_000L
            frames = 54_000
            cpuNs = 54_000L * 950_000
            gpuNs = 54_000L * 900_000
            gpuFrames = 54_000
        }
        val model = mapOf("megacity@30" to 30f, "megacity@60" to 71f)
        val days = Usage.days(listOf(h), CostModel(model), zone, LocalDate.of(2026, 10, 24), count = 2)
        val text = BatteryExport.text(
            BatteryData(
                header = listOf("fluid_motion 0.7.1 (build 10)"),
                settings = listOf("MEGACITY: AMBER · GLOW MAX · ZOOM 100% · QUALITY ULTRA"),
                calibration = "AUTO CALIBRATE · …",
                impacts = listOf("MEASURE IMPACT · MEGACITY", "MEASURE IMPACT · VELOCITY"),
                model = model,
                log = listOf(h),
                days = days,
            ),
            zone,
            label,
        )
        val sections = Regex("^(?:---|===) (.+?) (?:---|===)$", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1].substringBefore(" (") }.toList()
        assertEquals(listOf("fluid_motion BATTERY DATA", "SETTINGS", "AUTO CALIBRATE", "MEASURE IMPACT", "COST MODEL", "DAYS", "HOURS", "END"), sections)
        // Half an hour at 30 fps at +30 mA: 15 mAh; the still frame adds nothing.
        assertTrue(text, text.contains("2026-10-24 13h · 54000 · 0.95 ms · 0.90 ms · ≈ 15.00 mAh · megacity@30=30.0, megacity@0=6.0"))
        assertTrue(text, text.contains("MEGACITY 30 fps: +30 mA"))
        assertTrue(text, text.contains("2026-10-24 · 36 min · 30 min · 6 min · 54000 frames · 0.95 ms · 0.90 ms · ≈ 15.0 mAh\n    MEGACITY 30 fps 30 min · MEGACITY still 6 min"))
        assertTrue(text, text.contains("2026-10-23 · nothing on screen"))
        // The reports are kept whole, a blank line between them.
        assertTrue(text, text.contains("MEASURE IMPACT · MEGACITY\n\nMEASURE IMPACT · VELOCITY"))
    }

    @Test
    fun `before anything is measured or logged, it says so`() {
        val text = BatteryExport.text(BatteryData(listOf("h"), listOf("s"), "", emptyList(), emptyMap(), emptyList(), emptyList()), zone, label)
        assertTrue(text.contains("--- AUTO CALIBRATE ---\nnot run yet"))
        assertTrue(text.contains("none yet"))
        assertTrue(text.contains("none measured: a typical +${CostModel.TYPICAL_MA_PER_FPS} mA per frame a second"))
        assertTrue(text.endsWith("=== END ==="))
    }
}
