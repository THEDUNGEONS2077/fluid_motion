/*
 * lib/horizon/HorizonParams.kt: HORIZON's numbers, cyberdeck_terminal's
 * (src/wallpaper/horizon/params.ts), plus the few a phone needs of its own.
 *
 * Layer: lib (pure Kotlin, data only). The land is in world units: the
 * camera 1 unit above the ground, a row of land one cell deep. The screen
 * is only how it's seen. What differs from the web, and why, is marked
 * PHONE.
 */
package com.thedungeons2077.fluidmotion.lib.horizon

import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.rain.Span

/** HORIZON's numbers. */
object HorizonParams {
    /**
     * PHONE: how deep the land is (units). The web's is 64; past the haze's
     * end (FOG.end, 44) nothing shows, so 48 keeps the wrap out of sight
     * with a quarter fewer triangles to send through the GPU each frame.
     */
    const val DEPTH = 48f

    /** Where the haze's far wall stands (units): just inside the land's far end, covering the strip up to the horizon. */
    const val WALL = DEPTH - 2f

    /** PHONE: the screen shape the web's layout was made for (16:9). */
    const val REFERENCE_ASPECT = 16f / 9f

    /**
     * PHONE: how the cells shrink on a narrower screen: by (how much narrower)
     * to this power (HorizonView). A narrow screen sees a narrow slice of the
     * land, so at the web's cells only a cell or two would cross it. 0.8.0
     * shrank them by the full ratio (power 1: as many across as the web's
     * 16:9), but an upright phone's tall land then showed about four times
     * the web's rows: a crowd of small hills. The user asked for fewer hills,
     * closer to the reference (2026-10-28): the square root (as SUNSET's
     * camera) gives cells twice as big on the S25 Ultra, about half as many
     * across, and a quarter of the triangles.
     */
    const val CELL_NARROW_POWER = 0.5f

    /** PHONE: the cells' limits (units), and the most quads the land may have (zoomed out, cells grow to stay within it). */
    const val CELL_MIN = 0.2f
    const val CELL_MAX = 1.2f
    const val QUAD_BUDGET = 24_000

    /** Where each point may sit off its grid spot (shares of a cell): enough to read as random triangles, not a grid. */
    const val JITTER_X = 0.38f
    const val JITTER_Z = 0.2f

    /** The land's height (in cells): most points low, now and then a peak (the web's since 0.17.4: gentler). */
    val HEIGHT_LOW = Span(0f, 0.2f)
    const val PEAK_CHANCE = 0.14f
    val HEIGHT_PEAK = Span(0.26f, 0.48f)

    /** The camera: its height and the near clip (units). */
    const val CAMERA_HEIGHT = 1f
    const val NEAR = 0.25f

    /** The lens: focal length (share of the screen's height) and the horizon (share from the top): the land fills the bottom half. */
    const val FOCAL = 0.95f
    const val HORIZON = 0.5f

    /** How fast the camera glides (units a second at the web's cell of 1: a row every two seconds or so). */
    const val SPEED = 0.45f

    /** The haze over the land (units of depth): it starts thinning the land at `start`, hides it by `end`. */
    const val FOG_START = 3f
    const val FOG_END = 44f

    /** The edges: core width and glow reach (dp), and how much softer and wider the nearest are (within LINE_NEAR units). */
    const val LINE_WIDTH = 0.5f
    const val LINE_GLOW = 3.6f
    const val LINE_NEAR = 4.5f
    const val LINE_SOFTEN = 3.2f

    /**
     * The glow on the horizon: its core just above the middle (lift), the
     * core's and the glow's radius, the haze dome's radii (shares of the
     * screen's short side: PHONE, the web's height, which is its short side).
     */
    const val SUN_LIFT = 0.016f
    const val SUN_CORE = 0.011f
    const val SUN_GLOW = 0.22f
    const val DOME_RX = 0.26f * REFERENCE_ASPECT
    const val DOME_RY = 0.62f

    /** Per glow level: the edges' glow (0–1) and the horizon's glow (a multiplier). */
    class Glow(val edge: Float, val sun: Float)

    val GLOW: Map<GlowLevel, Glow> = mapOf(
        GlowLevel.LOW to Glow(0.2f, 0.85f),
        GlowLevel.MEDIUM to Glow(0.32f, 0.95f),
        GlowLevel.HIGH to Glow(0.45f, 1f),
        GlowLevel.MAX to Glow(0.62f, 1.1f),
    )

    /** The longest step the motion takes in one frame (s). */
    const val MAX_FRAME_STEP = 0.1f
}
