/*
 * lib/Quality.kt: how much detail a wallpaper is drawn with (QUALITY), and
 * AUTO CALIBRATE's rules for choosing it on a phone.
 *
 * Layer: lib (pure Kotlin). MEASURE IMPACT on the S25 Ultra (0.2.0) found
 * a frame costs about 1–1.7 ms of GPU there, and that the screen's refresh
 * rate is most of a wallpaper's cost. On weaker phones the GPU's share
 * grows, and what costs it most is pixels. So QUALITY is mostly
 * resolution: each level caps how many pixels a dp is drawn with, and the
 * picture is scaled up to the screen (smoothly) when that's fewer than the
 * screen has. At full resolution nothing extra is drawn at all.
 */
package com.thedungeons2077.fluidmotion.lib

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A detail level. Names are stored in settings: never rename one.
 * [maxPxPerDp] caps the drawing's resolution (the S25 Ultra's screen is
 * 2.8 px per dp at its default FHD+, 3.75 at WQHD+); [linkGlow] keeps
 * NEURAL NET's soft second stroke on its links (its widest pass) and DATA
 * FLOW's soft glow round its traces and halos round its packets.
 */
enum class Quality(val label: String, val maxPxPerDp: Float, val linkGlow: Boolean) {
    LOW("LOW", 1.5f, false),
    MEDIUM("MEDIUM", 2.25f, true),
    HIGH("HIGH", 3f, true),
    ULTRA("ULTRA", Float.POSITIVE_INFINITY, true);

    /** The share of the screen's resolution drawn at [density] (px per dp): 1 is full resolution. */
    fun scale(density: Float): Float = min(1f, maxPxPerDp / density)

    companion object {
        /** Until chosen or calibrated: 0.2's look (full resolution up to 3 px per dp). */
        val DEFAULT = HIGH

        /** The level named [name], or the default. */
        fun find(name: String?): Quality = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** How the rate and quality are chosen: calibrated for the phone, or by hand. Names are stored in settings. */
enum class PowerMode(val label: String) {
    AUTO("AUTO"),
    MANUAL("MANUAL");

    companion object {
        /** The mode named [name], or [fallback]. */
        fun find(name: String?, fallback: PowerMode): PowerMode = entries.firstOrNull { it.name == name } ?: fallback
    }
}

/**
 * AUTO CALIBRATE: each wallpaper is drawn on the phone at each quality in
 * turn, at the rate AUTO uses, and its frames timed (render/FrameMeter.kt).
 * Each gets the highest quality whose frames stay within budget: a small
 * share of the frame's time, so the GPU and CPU stay at their lowest,
 * most efficient clocks.
 */
object Calibration {
    /** The rate it measures at and AUTO draws at: BALANCED, where the screen can slow down too. */
    val PROFILE = PowerProfile.BALANCED

    /** Per step: time to settle (a new quality rebuilds the scene), then time measured (s). */
    const val SETTLE_SECONDS = 1
    const val MEASURE_SECONDS = 2

    /**
     * The budget a frame may cost (ms), GPU and CPU each: 3 ms is under a
     * tenth of a frame at 30 fps, so both stay mostly asleep at their
     * lowest clocks. (The S25 Ultra: 1–1.7 ms GPU, about 1 ms CPU.)
     */
    const val GPU_BUDGET_MS = 3f
    const val CPU_BUDGET_MS = 3f

    /** One quality measured: its frames' GPU and CPU time (ms). */
    data class Sample(val quality: Quality, val gpuMs: Float, val cpuMs: Float) {
        /** Within budget (a GPU time that couldn't be measured counts as within). */
        val within: Boolean get() = (gpuMs.isNaN() || gpuMs <= GPU_BUDGET_MS) && cpuMs <= CPU_BUDGET_MS

        /** For comparing costs: the larger of the two, as a share of its budget. */
        val load: Float get() = maxOf(if (gpuMs.isNaN()) 0f else gpuMs / GPU_BUDGET_MS, cpuMs / CPU_BUDGET_MS)
    }

    /** The quality to draw at: the highest within budget, or if none is, the cheapest measured. */
    fun choose(samples: List<Sample>): Quality =
        samples.filter { it.within }.maxByOrNull { it.quality.ordinal }?.quality
            ?: samples.minByOrNull { it.load }?.quality
            ?: Quality.DEFAULT

    /**
     * The profile AUTO draws a wallpaper at: BALANCED, or ECO if even its
     * chosen quality is over budget (a heavy wallpaper on a weak phone).
     * Each wallpaper its own (0.11.0): before, one over budget slowed every
     * wallpaper to ECO (the S25 Ultra's PRISM, 0.10.0).
     */
    fun profile(chosen: Sample?): PowerProfile = if (chosen == null || chosen.within) PROFILE else PowerProfile.ECO

    /**
     * What a quality really draws at on a screen of [density] (its scale,
     * rounded) and whether the link glow is on: qualities that come out the
     * same are measured once (on the S25 Ultra's FHD+ HIGH and ULTRA are
     * both full resolution).
     */
    fun key(quality: Quality, density: Float, usesLinkGlow: Boolean): String {
        val scale = (quality.scale(density) * 100).roundToInt()
        return if (usesLinkGlow) "$scale/${quality.linkGlow}" else "$scale"
    }
}
