/*
 * render/UsageRecorder.kt: a wallpaper's own log of its time on screen and
 * its frames, for INSIGHT (lib/Usage.kt).
 *
 * Layer: render (the loop's thread). It never wakes the phone and adds
 * nothing to a frame:
 *   - time on screen is kept as spans, opened and closed when the
 *     wallpaper is shown, hidden, or changes rate (the loop knows these
 *     anyway), not counted frame by frame;
 *   - frames and their cost come from FrameMeter's running totals, read
 *     only when the log is written;
 *   - the log is written when the wallpaper goes off screen, and every
 *     FLUSH_EVERY_MS while it stays on, checked between frames it draws
 *     anyway (a still wallpaper's time is written when it's hidden).
 */
package com.thedungeons2077.fluidmotion.render

import android.content.Context
import com.thedungeons2077.fluidmotion.lib.Usage
import com.thedungeons2077.fluidmotion.lib.UsageHour

/** Records one wallpaper's use. */
class UsageRecorder(private val context: Context) {
    // The span on screen now: its key (wallpaper and rate) and start (epoch ms; 0: off screen).
    private var spanKey = ""
    private var spanStart = 0L

    // What's new since the last write, by hour.
    private val pending = HashMap<Long, UsageHour>()
    private var lastWrite = System.currentTimeMillis()

    // The meter's totals at the last write.
    private var frames = 0L
    private var cpuNs = 0L
    private var gpuNs = 0L
    private var gpuFrames = 0L

    /** On screen as [kind] at [fps] (0: a still frame) from [now]: a new span if anything changed. */
    fun onScreen(kind: String, fps: Int, now: Long) {
        val key = Usage.key(kind, fps)
        if (spanStart != 0L && key == spanKey) return
        closeSpan(now)
        spanKey = key
        spanStart = now
    }

    /** Off screen from [now]. */
    fun offScreen(now: Long) {
        closeSpan(now)
        spanStart = 0L
    }

    /** Between frames: writes the log if it's been a while ([meter]: its totals give the frames since). */
    fun maybeWrite(meter: FrameMeter, now: Long) {
        if (now - lastWrite >= FLUSH_EVERY_MS) write(meter, now)
    }

    /** Writes what's new to the store (the span on screen carries on from [now]). */
    fun write(meter: FrameMeter, now: Long) {
        if (spanStart != 0L) {
            val key = spanKey
            closeSpan(now)
            spanKey = key
            spanStart = now
        }
        // The frames since the last write, counted in this hour (a write comes at least every few minutes).
        val h = pending.getOrPut(Usage.hourOf(now)) { UsageHour(Usage.hourOf(now)) }
        h.frames += meter.totalFrames - frames
        h.cpuNs += meter.totalCpuNs - cpuNs
        h.gpuNs += meter.totalGpuNs - gpuNs
        h.gpuFrames += meter.totalGpuFrames - gpuFrames
        frames = meter.totalFrames
        cpuNs = meter.totalCpuNs
        gpuNs = meter.totalGpuNs
        gpuFrames = meter.totalGpuFrames
        UsageStore.merge(context, pending.values, now)
        pending.clear()
        lastWrite = now
    }

    /** Adds the span on screen up to [now] to what's new. */
    private fun closeSpan(now: Long) {
        if (spanStart == 0L || now <= spanStart) return
        Usage.addTime(pending, spanKey, spanStart, now)
    }

    private companion object {
        /** How often the log is written while a wallpaper stays on screen (ms). */
        const val FLUSH_EVERY_MS = 10 * 60_000L
    }
}
