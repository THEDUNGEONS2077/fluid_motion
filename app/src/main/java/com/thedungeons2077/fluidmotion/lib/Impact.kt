/*
 * lib/Impact.kt: MEASURE IMPACT's arithmetic: what a wallpaper costs the
 * battery, measured on the phone itself.
 *
 * Layer: lib (pure Kotlin). The test (ui/HomeState.kt runs it) shows the
 * wallpaper full screen at each rate in turn, STILL first, and samples the
 * battery current once a second. Each rate's extra current over STILL is
 * the wallpaper's own cost: the screen, the radios and everything else
 * are the same in every step, so they cancel out.
 */
package com.thedungeons2077.fluidmotion.lib

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** MEASURE IMPACT. */
object Impact {
    /** One step of a run: what's shown, at what rate (0: a still frame). */
    data class Step(val label: String, val fps: Int)

    /** The steps: STILL is the base the others are measured against. */
    val STEPS = listOf(Step("STILL", 0)) + PowerProfile.entries.map { Step(it.label, it.fps) }

    /** How long each step runs (s), and the first part of it that's left out (the current settles after a change). */
    const val STEP_SECONDS = 45
    const val SETTLE_SECONDS = 10

    /** What one step measured: mean current (mA), and the frames' rate and cost (ms; NaN if not measured). */
    data class Result(val label: String, val fps: Int, val mA: Float, val drawnFps: Float, val cpuMs: Float, val gpuMs: Float, val tempC: Float)

    /**
     * The battery's current in mA, from BatteryManager's CURRENT_NOW reading
     * ([raw]): µA by the docs, but some phones (some Samsung versions among
     * them) give mA. A phone with its screen on draws hundreds of mA, so a
     * reading under 20 000 can only be mA. The sign varies too (discharging
     * is negative on most, positive on some): the size is what counts,
     * unplugged. Null if the phone doesn't report it.
     */
    fun milliamps(raw: Int): Float? = when {
        raw == 0 || raw == Int.MIN_VALUE -> null
        abs(raw) >= 20_000 -> abs(raw) / 1000f
        else -> abs(raw).toFloat()
    }

    /** The mean of the first [n] [values], the highest and lowest [trim] share left out (a spike from a notification, say). */
    fun trimmedMean(values: FloatArray, n: Int, trim: Float = 0.1f): Float {
        if (n <= 0) return Float.NaN
        val sorted = values.copyOf(n).also { it.sort() }
        val cut = (n * trim).toInt()
        var sum = 0.0
        for (i in cut until n - cut) sum += sorted[i]
        return (sum / (n - 2 * cut)).toFloat()
    }

    /** The battery's full capacity (mAh), from its charge now (µAh, CHARGE_COUNTER) and its level (%); null if either is missing. */
    fun capacityMah(chargeUah: Int, levelPercent: Int): Float? =
        if (chargeUah <= 0 || levelPercent <= 0) null else chargeUah / 1000f / (levelPercent / 100f)

    /** The share of a full battery an extra current takes in an hour (%). */
    fun percentPerHour(extraMa: Float, capacityMah: Float): Float = extraMa / capacityMah * 100f

    /** The report, to read and to paste: each rate's extra current over STILL, its cost in battery an hour, and its frames' cost. */
    fun report(results: List<Result>, capacityMah: Float?, about: String): String {
        val base = results.firstOrNull { it.fps == 0 }?.mA ?: Float.NaN
        val lines = mutableListOf("MEASURE IMPACT · $about")
        if (capacityMah != null) lines += "battery ${capacityMah.roundToInt()} mAh"
        for (r in results) {
            lines += if (r.fps == 0) {
                "${r.label}: ${r.mA.roundToInt()} mA (the base: screen and phone)"
            } else {
                val extra = r.mA - base
                val hour = if (capacityMah != null) " (%+.1f%% an hour)".format(Locale.UK, percentPerHour(extra, capacityMah)) else ""
                val gpu = if (r.gpuMs.isNaN()) "" else " · GPU %.2f ms".format(Locale.UK, r.gpuMs)
                // Pieces formatted one by one: the hour's text has a % sign of its own.
                "${r.label} ${r.fps}: " + "%+d mA".format(Locale.UK, extra.roundToInt()) + hour +
                    " · %.0f fps drawn · CPU %.2f ms".format(Locale.UK, r.drawnFps, r.cpuMs) + gpu
            }
        }
        val temps = results.map { it.tempC }.filter { !it.isNaN() }
        if (temps.isNotEmpty()) lines += "battery %.1f → %.1f °C".format(Locale.UK, temps.first(), temps.last())
        return lines.joinToString("\n")
    }
}
