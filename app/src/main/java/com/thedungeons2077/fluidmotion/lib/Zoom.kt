/*
 * lib/Zoom.kt: ZOOM, each wallpaper's own (user request, 2026-10-23: zoom
 * in, and set any wallpaper's default zoom): the steps, as cyberdeck_terminal
 * has them (src/lib/layout.ts ZOOM_STEPS).
 *
 * Layer: lib (pure Kotlin). A wallpaper at zoom z is laid out for a screen
 * 1/z as big (in dp) and drawn z times as large: its glyphs, nodes, traces
 * and chips grow, and fewer fit. The resolution it's drawn at doesn't
 * change (QUALITY sets that from the screen's own density), so zoomed in,
 * it's as sharp; zoomed out, there's more of it to draw.
 */
package com.thedungeons2077.fluidmotion.lib

import kotlin.math.abs
import kotlin.math.roundToInt

/** The zoom steps and the rules for moving between them. */
object Zoom {
    /** Chrome's own zoom levels from 50% to 200%, smallest first (the web's). */
    val STEPS = floatArrayOf(0.5f, 0.67f, 0.75f, 0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)

    /** As designed: a wallpaper's zoom until one is chosen. */
    const val DEFAULT = 1f

    /** The smallest and largest. */
    val MIN = STEPS.first()
    val MAX = STEPS.last()

    /** The step nearest [zoom] (anything out of range comes back in it). */
    fun nearest(zoom: Float): Float {
        if (!zoom.isFinite()) return DEFAULT
        var best = DEFAULT
        for (s in STEPS) if (abs(s - zoom) < abs(best - zoom)) best = s
        return best
    }

    /** The next step in ([direction] 1) or out (-1) from [zoom]; at either end, the end. */
    fun step(zoom: Float, direction: Int): Float {
        val at = STEPS.indexOfFirst { it == nearest(zoom) }
        return STEPS[(at + direction).coerceIn(0, STEPS.size - 1)]
    }

    /** As the app shows it: `125%`. */
    fun label(zoom: Float): String = "${(zoom * 100).roundToInt()}%"
}
