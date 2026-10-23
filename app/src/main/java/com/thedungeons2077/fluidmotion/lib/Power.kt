/*
 * lib/Power.kt: how often a wallpaper draws: the power profiles, and the
 * rule that turns a profile and the phone's state into a frame rate.
 *
 * Layer: lib (pure Kotlin). The battery objective lives here (user request,
 * 2026-10-23: "without battery life and phone performance taking a hit"):
 *   - a wallpaper only draws while it's on screen (the render loop stops
 *     otherwise: wallpaper/RenderLoop.kt), so most of the day it costs
 *     nothing at all;
 *   - while it is on screen it draws at a capped rate: the motion is slow,
 *     so 30 frames a second looks the same as 120 at a quarter of the work
 *     (and lets an adaptive display like the S25 Ultra's drop its refresh);
 *   - battery saver freezes it to a still frame; a hot phone slows it, then
 *     freezes it (thermal status from PowerManager).
 * Rates divide 120 and 60 evenly (20, 30, 60), so frames land on even vsync
 * steps on 60/120 Hz screens: no judder.
 */
package com.thedungeons2077.fluidmotion.lib

/** A power profile: the frame rate while the wallpaper is on screen. Names are stored in settings. */
enum class PowerProfile(val fps: Int, val label: String) {
    ECO(20, "ECO"),
    BALANCED(30, "BALANCED"),
    SMOOTH(60, "SMOOTH");

    companion object {
        /** A new install's profile. */
        val DEFAULT = BALANCED

        /** The profile named [name], or the default. */
        fun find(name: String?): PowerProfile = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Thermal statuses, as android.os.PowerManager numbers them (THERMAL_STATUS_*). */
object Thermal {
    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3
}

/** The phone's state, as far as the frame rate cares. */
data class PowerState(
    /** Battery saver is on. */
    val powerSave: Boolean = false,
    /** The thermal status (Thermal.*). */
    val thermal: Int = Thermal.NONE,
)

/** What the user chose about power. */
data class PowerChoice(
    val profile: PowerProfile = PowerProfile.DEFAULT,
    /** Freeze to a still frame in battery saver (default on). */
    val stillInSaver: Boolean = true,
    /** Slow down, then freeze, when the phone is hot (default on). */
    val coolWhenHot: Boolean = true,
)

/**
 * The frame rate to draw at now: 0 means draw one still frame and stop.
 * Battery saver (if chosen) and a severe thermal status freeze it; a
 * moderate thermal status halves it (to no fewer than 10).
 */
fun frameRate(choice: PowerChoice, state: PowerState): Int {
    if (state.powerSave && choice.stillInSaver) return 0
    if (choice.coolWhenHot && state.thermal >= Thermal.SEVERE) return 0
    if (choice.coolWhenHot && state.thermal >= Thermal.MODERATE) return maxOf(10, choice.profile.fps / 2)
    return choice.profile.fps
}

/**
 * When to draw the next frame (ns), given the last one and the rate: one
 * interval after the last, or now if that's already past (after a pause the
 * rhythm restarts instead of rushing to catch up).
 */
fun nextFrameAt(lastFrameNs: Long, nowNs: Long, fps: Int): Long {
    val interval = 1_000_000_000L / fps
    val next = lastFrameNs + interval
    return if (lastFrameNs == 0L || next < nowNs) nowNs else next
}
