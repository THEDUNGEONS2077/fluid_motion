/*
 * lib/Usage.kt: INSIGHT's log and arithmetic: how long the wallpapers were
 * on screen, at what rate, how many frames they drew and what those cost,
 * hour by hour, and what that comes to in battery.
 *
 * Layer: lib (pure Kotlin; java.time for days). The log is kept by
 * render/UsageRecorder.kt, which writes it only when a wallpaper goes off
 * screen or every few minutes between frames drawn anyway: keeping it
 * costs nothing measurable. The battery estimate uses what MEASURE IMPACT
 * found on this phone (the extra current at each rate) where it has
 * measured, a typical figure otherwise: the screen refreshing for the
 * wallpaper is most of its cost, which the system's battery screen counts
 * as the screen's, not the app's.
 */
package com.thedungeons2077.fluidmotion.lib

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/**
 * One hour's use ([hour]: hours since 1970, UTC): time on screen per
 * wallpaper and rate ("matrix-rain@30" → ms; rate 0 is a still frame),
 * frames drawn, and their CPU and GPU time (ns; GPU over [gpuFrames], the
 * frames the GPU could be timed for).
 */
class UsageHour(val hour: Long) {
    val onScreen = HashMap<String, Long>()
    var frames = 0L
    var cpuNs = 0L
    var gpuNs = 0L
    var gpuFrames = 0L

    /** Adds [other]'s figures to this hour's. */
    fun add(other: UsageHour) {
        for ((k, ms) in other.onScreen) onScreen[k] = (onScreen[k] ?: 0L) + ms
        frames += other.frames
        cpuNs += other.cpuNs
        gpuNs += other.gpuNs
        gpuFrames += other.gpuFrames
    }

    /** Nothing recorded. */
    val empty: Boolean get() = onScreen.isEmpty() && frames == 0L
}

/**
 * What a wallpaper adds to the battery's current (mA) at a rate. Measured
 * figures ("matrix-rain@30" → extra mA over a still frame, from MEASURE
 * IMPACT) come first: a wallpaper's cost is close to proportional to its
 * rate (the S25 Ultra's SUNSET, 0.8.1: 1.46, 1.47 and 1.45 mA per frame a
 * second at 24, 30 and 60 fps), so its figure is the median of its measured
 * mA per frame a second, times the rate. One bad reading among three can't
 * move a median (0.11.0; before, HORIZON's ECO read +149 mA and that was
 * taken as is). Then the same rate measured on other wallpapers, then the
 * measured mA per frame a second, then a typical figure (the S25 Ultra,
 * 0.2.0: about 1.2 mA per frame a second).
 */
class CostModel(private val measured: Map<String, Float>) {
    /** Whether any of it was measured on this phone. */
    val fromThisPhone: Boolean get() = measured.isNotEmpty()

    /** Extra current (mA) for [kind] drawing at [fps] (0: a still frame, nothing extra). */
    fun milliamps(kind: String, fps: Int): Float {
        if (fps <= 0) return 0f
        // This wallpaper's own: the median mA per frame a second (two figures: their mean), times the rate.
        val own = measured.mapNotNull { (k, mA) ->
            if (k.substringBefore('@') != kind) null else k.substringAfter('@').toIntOrNull()?.takeIf { it > 0 }?.let { max(0f, mA) / it }
        }.sorted()
        if (own.isNotEmpty()) {
            val mid = own.size / 2
            val median = if (own.size % 2 == 1) own[mid] else (own[mid - 1] + own[mid]) / 2f
            return median * fps
        }
        val sameRate = measured.filterKeys { it.endsWith("@$fps") }.values
        if (sameRate.isNotEmpty()) return max(0f, sameRate.average().toFloat())
        val perFps = measured.mapNotNull { (k, mA) -> k.substringAfter('@').toIntOrNull()?.takeIf { it > 0 }?.let { mA / it } }
        val rate = if (perFps.isNotEmpty()) perFps.average().toFloat() else TYPICAL_MA_PER_FPS
        return max(0f, rate * fps)
    }

    companion object {
        /** Extra mA per frame a second, typical (S25 Ultra, MEASURE IMPACT, 0.2.0: +8 to +87 mA at 30 to 60 fps). */
        const val TYPICAL_MA_PER_FPS = 1.2f

        /** The measured figures, from their stored form ("key=mA;key=mA"). */
        fun decode(text: String): Map<String, Float> = text.split(';').mapNotNull { part ->
            val k = part.substringBefore('=', "")
            val v = part.substringAfter('=', "").toFloatOrNull()
            if (k.isEmpty() || v == null) null else k to v
        }.toMap()

        /** The measured figures in their stored form. */
        fun encode(measured: Map<String, Float>): String = measured.entries.joinToString(";") { "${it.key}=${it.value}" }
    }
}

/** One day's use, summed from its hours. */
data class UsageDay(
    val date: LocalDate,
    /** On screen in all (ms), drawing (rate above 0), and still (battery saver, heat). */
    val onScreenMs: Long,
    val drawingMs: Long,
    val stillMs: Long,
    val frames: Long,
    /** Mean CPU and GPU time of a frame (ms; NaN if none were timed). */
    val cpuMs: Float,
    val gpuMs: Float,
    /** The estimated battery used (mAh), and time on screen per wallpaper and rate (ms), longest first. */
    val mAh: Float,
    val breakdown: List<Pair<String, Long>>,
)

/** INSIGHT's log and sums. */
object Usage {
    /** How long the log keeps (hours): a week back, and today. */
    const val KEEP_HOURS = 24 * 8

    /** The key of a wallpaper at a rate in the log. */
    fun key(kind: String, fps: Int) = "$kind@$fps"

    /** The hour (hours since 1970, UTC) of [epochMs]. */
    fun hourOf(epochMs: Long): Long = Math.floorDiv(epochMs, HOUR_MS)

    /**
     * Adds time on screen from [startMs] to [endMs] (epoch ms) to [hours]
     * under [key], split at the hours it crosses.
     */
    fun addTime(hours: MutableMap<Long, UsageHour>, key: String, startMs: Long, endMs: Long) {
        var t = startMs
        while (t < endMs) {
            val h = hourOf(t)
            val until = minOf(endMs, (h + 1) * HOUR_MS)
            val bucket = hours.getOrPut(h) { UsageHour(h) }
            bucket.onScreen[key] = (bucket.onScreen[key] ?: 0L) + (until - t)
            t = until
        }
    }

    /** [deltas] merged into [log] (the log's hours are added to), the hours older than [keepFrom] dropped; sorted by hour. */
    fun merge(log: List<UsageHour>, deltas: Collection<UsageHour>, keepFrom: Long): List<UsageHour> {
        val byHour = LinkedHashMap<Long, UsageHour>()
        for (h in log) if (h.hour >= keepFrom) byHour.getOrPut(h.hour) { UsageHour(h.hour) }.add(h)
        for (d in deltas) if (d.hour >= keepFrom && !d.empty) byHour.getOrPut(d.hour) { UsageHour(d.hour) }.add(d)
        return byHour.values.sortedBy { it.hour }
    }

    /** The log in its stored form: one line an hour, "hour|frames|cpuNs|gpuNs|gpuFrames|key=ms,key=ms". */
    fun encode(log: List<UsageHour>): String = log.joinToString("\n") { h ->
        "${h.hour}|${h.frames}|${h.cpuNs}|${h.gpuNs}|${h.gpuFrames}|" + h.onScreen.entries.joinToString(",") { "${it.key}=${it.value}" }
    }

    /** The log from its stored form (lines it can't read are left out). */
    fun decode(text: String): List<UsageHour> = text.lineSequence().mapNotNull { line ->
        val f = line.split('|')
        if (f.size != 6) return@mapNotNull null
        val h = UsageHour(f[0].toLongOrNull() ?: return@mapNotNull null)
        h.frames = f[1].toLongOrNull() ?: 0L
        h.cpuNs = f[2].toLongOrNull() ?: 0L
        h.gpuNs = f[3].toLongOrNull() ?: 0L
        h.gpuFrames = f[4].toLongOrNull() ?: 0L
        if (f[5].isNotEmpty()) {
            for (part in f[5].split(',')) {
                val ms = part.substringAfter('=', "").toLongOrNull() ?: continue
                h.onScreen[part.substringBefore('=')] = ms
            }
        }
        h
    }.toList()

    /** The battery an hour used (mAh), estimated with [model]. */
    fun mAh(hour: UsageHour, model: CostModel): Float =
        hour.onScreen.entries.sumOf { (k, ms) ->
            val kind = k.substringBefore('@')
            val fps = k.substringAfter('@').toIntOrNull() ?: 0
            model.milliamps(kind, fps).toDouble() * ms / HOUR_MS
        }.toFloat()

    /** The last [count] days to [today] (oldest first, today last) in [zone], summed from [log] and estimated with [model]. */
    fun days(log: List<UsageHour>, model: CostModel, zone: ZoneId, today: LocalDate, count: Int = 7): List<UsageDay> {
        val first = today.minusDays((count - 1).toLong())
        val byDay = log.groupBy { Instant.ofEpochSecond(it.hour * 3600).atZone(zone).toLocalDate() }
        return (0 until count).map { i ->
            val date = first.plusDays(i.toLong())
            val hours = byDay[date].orEmpty()
            val times = HashMap<String, Long>()
            for (h in hours) for ((k, ms) in h.onScreen) times[k] = (times[k] ?: 0L) + ms
            val still = times.filterKeys { it.endsWith("@0") }.values.sum()
            val all = times.values.sum()
            val frames = hours.sumOf { it.frames }
            val gpuFrames = hours.sumOf { it.gpuFrames }
            UsageDay(
                date = date,
                onScreenMs = all,
                drawingMs = all - still,
                stillMs = still,
                frames = frames,
                cpuMs = if (frames > 0) hours.sumOf { it.cpuNs } / 1e6f / frames else Float.NaN,
                gpuMs = if (gpuFrames > 0) hours.sumOf { it.gpuNs } / 1e6f / gpuFrames else Float.NaN,
                mAh = hours.sumOf { mAh(it, model).toDouble() }.toFloat(),
                breakdown = times.entries.sortedByDescending { it.value }.map { it.key to it.value },
            )
        }
    }

    private const val HOUR_MS = 3_600_000L
}
