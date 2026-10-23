/*
 * render/FrameMeter.kt: what each frame really costs, measured on the
 * phone: CPU time of the render thread, GPU time (where the driver can
 * time it), the rate actually drawn, and frames that came late.
 *
 * Layer: render (GL thread only). It answers "what does a wallpaper cost?"
 * with numbers instead of guesses (STATUS, MEASURE IMPACT). It costs
 * almost nothing itself: no allocation per frame, and GPU timer queries
 * are read a few frames late from a small ring, so the CPU never waits
 * for the GPU.
 */
package com.thedungeons2077.fluidmotion.render

import android.opengl.GLES30
import android.os.Debug

/** A summary of the frames since the last [FrameMeter.take]. */
data class FrameCost(
    /** Frames drawn a second (achieved, not asked for). */
    val fps: Float,
    /** CPU time of the render thread per frame (ms): simulation, GL calls, swap. */
    val cpuMs: Float,
    /** GPU time per frame (ms), or NaN where the driver can't time it. */
    val gpuMs: Float,
    /** Share of frames that came more than half an interval late (0–1). */
    val late: Float,
)

/** Measures frames; call [begin] and [end] round each frame's drawing (context current). */
class FrameMeter {
    /** Whether the GPU can be timed here (GL_EXT_disjoint_timer_query). */
    var gpuTimer = false
        private set

    private val queries = IntArray(RING)
    private val pending = BooleanArray(RING)
    private var slot = 0
    private var timing = false
    private val scratch = IntArray(1)

    private var frames = 0
    private var cpuNs = 0L
    private var gpuNs = 0L
    private var gpuFrames = 0
    private var lateFrames = 0
    private var since = 0L
    private var cpuStart = 0L

    /** Sets up for the current context (once per context). */
    fun setUp() {
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        gpuTimer = "GL_EXT_disjoint_timer_query" in extensions
        if (gpuTimer) GLES30.glGenQueries(RING, queries, 0)
        pending.fill(false)
        // Clear any disjoint flag left from before (reading it resets it).
        if (gpuTimer) GLES30.glGetIntegerv(GPU_DISJOINT_EXT, scratch, 0)
        since = System.nanoTime()
    }

    /** A frame starts: [late] if it came more than half an interval after it was due. */
    fun begin(late: Boolean) {
        cpuStart = threadTime()
        if (late) lateFrames++
        if (!gpuTimer) return
        collect()
        // Time this frame unless its slot's last result hasn't come back yet.
        timing = !pending[slot]
        if (timing) GLES30.glBeginQuery(TIME_ELAPSED_EXT, queries[slot])
    }

    /** The frame's GL work is all issued (call before the swap). */
    fun endGpu() {
        if (!timing) return
        GLES30.glEndQuery(TIME_ELAPSED_EXT)
        pending[slot] = true
        slot = (slot + 1) % RING
        timing = false
    }

    /** The frame is done (after the swap). */
    fun end() {
        cpuNs += threadTime() - cpuStart
        frames++
    }

    /** Reads every timer result that's ready (never waits). */
    private fun collect() {
        GLES30.glGetIntegerv(GPU_DISJOINT_EXT, scratch, 0)
        // Something upset the GPU's clock (a frequency change, a power state): the results in flight can't be trusted.
        val disjoint = scratch[0] != 0
        for (i in 0 until RING) {
            if (!pending[i]) continue
            GLES30.glGetQueryObjectuiv(queries[i], GLES30.GL_QUERY_RESULT_AVAILABLE, scratch, 0)
            if (scratch[0] == 0) continue
            GLES30.glGetQueryObjectuiv(queries[i], GLES30.GL_QUERY_RESULT, scratch, 0)
            pending[i] = false
            if (disjoint) continue
            gpuNs += scratch[0].toLong() and 0xffffffffL
            gpuFrames++
        }
    }

    /** The summary since the last call, then starts over. */
    fun take(): FrameCost {
        val now = System.nanoTime()
        val seconds = ((now - since) / 1e9f).coerceAtLeast(1e-3f)
        val cost = FrameCost(
            fps = frames / seconds,
            cpuMs = if (frames > 0) cpuNs / 1e6f / frames else 0f,
            gpuMs = if (gpuFrames > 0) gpuNs / 1e6f / gpuFrames else Float.NaN,
            late = if (frames > 0) lateFrames.toFloat() / frames else 0f,
        )
        frames = 0
        cpuNs = 0L
        gpuNs = 0L
        gpuFrames = 0
        lateFrames = 0
        since = now
        return cost
    }

    /** Frees the queries (context current). */
    fun release() {
        if (gpuTimer) GLES30.glDeleteQueries(RING, queries, 0)
        gpuTimer = false
    }

    private companion object {
        /** Timer queries in flight at most: results come back within a few frames. */
        const val RING = 4

        /** EXT_disjoint_timer_query's names (not in GLES30). */
        const val TIME_ELAPSED_EXT = 0x88BF
        const val GPU_DISJOINT_EXT = 0x8FBB

        /** This thread's CPU time (ns), or wall time where the kernel doesn't keep it. */
        fun threadTime(): Long {
            val t = Debug.threadCpuTimeNanos()
            return if (t >= 0) t else System.nanoTime()
        }
    }
}
