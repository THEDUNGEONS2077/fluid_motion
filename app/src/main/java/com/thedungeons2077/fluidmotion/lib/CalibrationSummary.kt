/*
 * lib/CalibrationSummary.kt: what AUTO CALIBRATE found, made readable (the
 * user, 0.11.0: "a summary and recommended manual settings … with a
 * breakdown of what the stats will be at different settings … a
 * recommended overall configuration").
 *
 * Layer: lib (pure Kotlin). From each wallpaper's measured frames at each
 * quality (Calibration.Sample) it works out:
 *   - AUTO's pick for each wallpaper (its quality, and its own rate:
 *     BALANCED, or ECO for one over budget even at its cheapest);
 *   - three ways to set MANUAL (one rate and one quality for every
 *     wallpaper): LONGEST BATTERY, RECOMMENDED and SMOOTHEST, each with what
 *     it costs: the battery an hour on screen (lib/Usage.kt CostModel:
 *     MEASURE IMPACT's figures for this phone, or a typical one), how busy
 *     the GPU gets for the heaviest wallpaper, and which wallpapers go over
 *     the frame budget.
 * The samples are kept in settings in a line of text (encode/decode), so
 * the summary is there after a restart, without calibrating again.
 */
package com.thedungeons2077.fluidmotion.lib

import java.util.Locale

/** AUTO CALIBRATE's summary and its recommendations. */
object CalibrationSummary {
    /** One wallpaper's measurements: its id (the cost model's key), name, and its frames at each quality. */
    data class Measured(val id: String, val label: String, val samples: List<Calibration.Sample>) {
        /** Its frames at [q] (null if not measured). */
        fun at(q: Quality): Calibration.Sample? = samples.firstOrNull { it.quality == q }

        /** Over budget even at its cheapest quality: a heavy wallpaper on this phone. */
        val heavy: Boolean get() = samples.isNotEmpty() && samples.none { it.within }
    }

    /** AUTO's pick for one wallpaper. */
    data class Pick(val label: String, val quality: Quality, val profile: PowerProfile, val sample: Calibration.Sample?)

    /** One way to set MANUAL, and what it costs. */
    data class Preset(
        val name: String,
        val about: String,
        val profile: PowerProfile,
        val quality: Quality,
        /** The rate it draws at on this display. */
        val fps: Int,
        /** Extra current while on screen (mA): the wallpapers' mean, and the dearest. */
        val meanMa: Float,
        val maxMa: Float,
        /** The share of the time the GPU is busy for the heaviest wallpaper (0–1; NaN if the GPU couldn't be timed). */
        val gpuBusy: Float,
        /** The wallpapers over the frame budget at its quality. */
        val over: List<String>,
        val total: Int,
    )

    /** The summary. */
    data class Summary(val picks: List<Pick>, val presets: List<Preset>, val recommended: Preset, val heavy: List<String>)

    /** A frame must leave the GPU and CPU this much of a 60 fps frame (ms) for SMOOTHEST to pick a quality: half of it, so 60 fps holds. */
    const val SMOOTH_FRAME_MS = 1000f / 60f / 2f

    /**
     * The summary for [measured] wallpapers on a display with [refreshRates],
     * the battery from [model].
     */
    fun of(measured: List<Measured>, refreshRates: List<Float>, model: CostModel): Summary {
        val picks = measured.map { m ->
            val q = Calibration.choose(m.samples)
            val s = m.at(q)
            Pick(m.label, q, if (s == null || s.within) Calibration.PROFILE else PowerProfile.ECO, s)
        }
        val heavy = measured.filter { it.heavy }
        // RECOMMENDED: the sharpest quality every wallpaper but the heavy ones draws within budget (they're over at any).
        val light = measured - heavy.toSet()
        // None fits anywhere (a weak phone): the cheapest, not the sharpest (every one of none fits trivially).
        val recommendedQ = if (light.isEmpty()) Quality.LOW else Quality.entries.lastOrNull { q -> light.all { it.at(q)?.within != false } } ?: Quality.LOW
        // SMOOTHEST: the sharpest quality every wallpaper draws in under half a 60 fps frame.
        val smoothQ = Quality.entries.lastOrNull { q ->
            measured.all { m -> m.at(q)?.let { (it.gpuMs.isNaN() || it.gpuMs <= SMOOTH_FRAME_MS) && it.cpuMs <= SMOOTH_FRAME_MS } != false }
        } ?: Quality.LOW
        fun preset(name: String, about: String, profile: PowerProfile, q: Quality): Preset {
            val fps = profileFps(profile, refreshRates)
            val mA = measured.map { model.milliamps(it.id, fps) }
            val busy = measured.mapNotNull { m -> m.at(q)?.gpuMs?.takeIf { !it.isNaN() } }.maxOrNull()?.let { it * fps / 1000f } ?: Float.NaN
            return Preset(
                name = name,
                about = about,
                profile = profile,
                quality = q,
                fps = fps,
                meanMa = if (mA.isEmpty()) 0f else mA.average().toFloat(),
                maxMa = mA.maxOrNull() ?: 0f,
                gpuBusy = busy,
                over = measured.filter { it.at(q)?.within == false }.map { it.label },
                total = measured.size,
            )
        }
        val battery = preset("LONGEST BATTERY", "The fewest frames, at the lowest quality: the least the wallpapers can cost.", PowerProfile.ECO, Quality.LOW)
        val recommended = preset(
            "RECOMMENDED",
            "30 frames a second, at the sharpest quality " +
                (if (heavy.isEmpty()) "every wallpaper" else "every wallpaper but ${names(heavy.map { it.label })}") + " draws within budget here.",
            PowerProfile.BALANCED,
            recommendedQ,
        )
        val smooth = preset("SMOOTHEST", "60 frames a second, at the sharpest quality every wallpaper keeps up with there.", PowerProfile.SMOOTH, smoothQ)
        return Summary(picks, listOf(battery, recommended, smooth), recommended, heavy.map { it.label })
    }

    /** "A", "A and B", "A, B and C". */
    fun names(list: List<String>): String = when (list.size) {
        0 -> ""
        1 -> list[0]
        else -> list.dropLast(1).joinToString(", ") + " and " + list.last()
    }

    /** The samples in their stored form: "id:QUALITY:gpu:cpu", ';' between (gpu "NaN" if it couldn't be timed). */
    fun encode(samples: Map<String, List<Calibration.Sample>>): String =
        samples.flatMap { (id, list) -> list.map { "$id:${it.quality.name}:${"%.3f".format(Locale.UK, it.gpuMs)}:${"%.3f".format(Locale.UK, it.cpuMs)}" } }.joinToString(";")

    /** The samples from their stored form, by wallpaper id; anything unreadable is left out. */
    fun decode(text: String): Map<String, List<Calibration.Sample>> {
        val out = LinkedHashMap<String, MutableList<Calibration.Sample>>()
        for (part in text.split(';')) {
            val f = part.split(':')
            if (f.size != 4) continue
            val q = Quality.entries.firstOrNull { it.name == f[1] } ?: continue
            val gpu = f[2].toFloatOrNull() ?: continue
            val cpu = f[3].toFloatOrNull() ?: continue
            out.getOrPut(f[0]) { mutableListOf() } += Calibration.Sample(q, gpu, cpu)
        }
        return out
    }
}
