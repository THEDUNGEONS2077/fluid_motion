/*
 * lib/BatteryExport.kt: COPY BATTERY DATA: everything the app knows about
 * its wallpapers' battery use, as one plain text to paste (into a chat,
 * for tuning them).
 *
 * Layer: lib (pure Kotlin; java.time for local days and hours). What goes
 * in, in order:
 *   - who and what: the app's version, the phone, the time, the battery;
 *   - each wallpaper's settings (palette, glow, zoom, quality) and the power choices;
 *   - the last AUTO CALIBRATE;
 *   - the latest MEASURE IMPACT of each wallpaper;
 *   - the cost model INSIGHT estimates with (the measured extra mA per wallpaper and rate);
 *   - INSIGHT's days, then its log hour by hour (local time), a week back.
 * Sections start with "--- NAME ---", so a reader (or a script) can find
 * them; figures are rounded to what's worth reading.
 */
package com.thedungeons2077.fluidmotion.lib

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the export is made of (gathered by the app; formatted here). */
data class BatteryData(
    /** Lines about the app, the phone and the battery now. */
    val header: List<String>,
    /** One line a wallpaper (its settings), then the power choices. */
    val settings: List<String>,
    /** The last AUTO CALIBRATE report ("" if none). */
    val calibration: String,
    /** The latest MEASURE IMPACT report of each wallpaper that has one. */
    val impacts: List<String>,
    /** The measured extra mA per "kind@fps" (CostModel's own figures). */
    val model: Map<String, Float>,
    /** INSIGHT's log (lib/Usage.kt), oldest first. */
    val log: List<UsageHour>,
    /** INSIGHT's days, oldest first. */
    val days: List<UsageDay>,
)

/** Formats COPY BATTERY DATA. */
object BatteryExport {
    private val HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH'h'", Locale.UK)

    /** The whole export, in [zone]'s local time; [label] names a log key's wallpaper ("matrix-rain" → "MATRIX RAIN"). */
    fun text(data: BatteryData, zone: ZoneId, label: (String) -> String): String {
        val model = CostModel(data.model)
        val out = mutableListOf("=== fluid_motion BATTERY DATA ===")
        out += data.header
        out += "--- SETTINGS ---"
        out += data.settings
        out += "--- AUTO CALIBRATE ---"
        out += data.calibration.ifEmpty { "not run yet" }
        out += "--- MEASURE IMPACT (latest of each wallpaper) ---"
        out += if (data.impacts.isEmpty()) listOf("none yet") else data.impacts.flatMap { listOf(it, "") }.dropLast(1)
        out += "--- COST MODEL (extra mA over a still frame, measured; INSIGHT estimates with these) ---"
        out += if (data.model.isEmpty()) {
            listOf("none measured: a typical +${CostModel.TYPICAL_MA_PER_FPS} mA per frame a second")
        } else {
            data.model.entries.sortedBy { it.key }.map { (k, mA) -> "${key(k, label)}: %+.0f mA".format(Locale.UK, mA) }
        }
        out += "--- DAYS (on screen · drawing · still · frames · CPU and GPU a frame · ≈ battery; then where the time went) ---"
        out += data.days.map { d ->
            if (d.onScreenMs == 0L) {
                "${d.date} · nothing on screen"
            } else {
                "${d.date} · ${minutes(d.onScreenMs)} · ${minutes(d.drawingMs)} · ${minutes(d.stillMs)} · ${d.frames} frames · ${ms(d.cpuMs)} · ${ms(d.gpuMs)} · ≈ %.1f mAh".format(Locale.UK, d.mAh) +
                    "\n    " + d.breakdown.joinToString(" · ") { (k, t) -> "${key(k, label)} ${minutes(t)}" }
            }
        }
        out += "--- HOURS (local; frames · CPU and GPU a frame · ≈ battery · minutes on screen per wallpaper and rate) ---"
        out += if (data.log.isEmpty()) {
            listOf("none yet")
        } else {
            data.log.filter { !it.empty }.map { h ->
                val at = Instant.ofEpochSecond(h.hour * 3600).atZone(zone).format(HOUR)
                val cpu = if (h.frames > 0) h.cpuNs / 1e6f / h.frames else Float.NaN
                val gpu = if (h.gpuFrames > 0) h.gpuNs / 1e6f / h.gpuFrames else Float.NaN
                val times = h.onScreen.entries.sortedByDescending { it.value }.joinToString(", ") { (k, t) -> "${k}=${"%.1f".format(Locale.UK, t / 60_000f)}" }
                "$at · ${h.frames} · ${ms(cpu)} · ${ms(gpu)} · ≈ %.2f mAh · $times".format(Locale.UK, Usage.mAh(h, model))
            }
        }
        out += "=== END ==="
        return out.joinToString("\n")
    }

    /** A log key as words: "matrix-rain@30" → "MATRIX RAIN 30 fps", "@0" → "… still". */
    private fun key(k: String, label: (String) -> String): String {
        val fps = k.substringAfter('@').toIntOrNull() ?: 0
        return "${label(k.substringBefore('@'))} ${if (fps == 0) "still" else "$fps fps"}"
    }

    /** Milliseconds as minutes ("134 min"). */
    private fun minutes(ms: Long): String = "${(ms + 30_000) / 60_000} min"

    /** A frame's time ("0.93 ms"), or "n/a". */
    private fun ms(v: Float): String = if (v.isNaN()) "n/a" else "%.2f ms".format(Locale.UK, v)
}
