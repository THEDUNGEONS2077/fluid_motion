/*
 * lib/rain/RainParams.kt: the matrix rain's tunable numbers, the same as
 * cyberdeck_terminal's (src/wallpaper/matrix-rain/params.ts), which were
 * matched to the reference wallpaper (wallhaven-01ye3v2.jpg).
 *
 * Layer: lib (data). Sizes are in dp here (CSS px there: the same scale).
 */
package com.thedungeons2077.fluidmotion.lib.rain

import com.thedungeons2077.fluidmotion.lib.GlowLevel
import kotlin.math.floor
import kotlin.math.pow

/** One depth layer of rain (sizes in dp; speeds in rows a second). */
data class RainLayer(
    val font: Float,
    val colW: Float,
    val rowH: Float,
    val speed: ClosedFloatingPointRange<Float>,
    /** Share of columns with a drop falling at any moment. */
    val occupancy: Float,
    /** Body glyph opacity range; each drop gets one value. */
    val alpha: ClosedFloatingPointRange<Float>,
    /** Chance a drop's head is drawn pale and glowing. */
    val headChance: Float,
)

/** One glow level for the rain: the glow colour's strength, the heads' blur and layers, the bodies' blur (dp). */
data class RainGlow(val alpha: Float, val head: Float, val headLayers: Int, val body: Float)

/** The rain's numbers. */
object RainParams {
    /** The three layers, back to front. */
    val LAYERS = listOf(
        RainLayer(8f, 7f, 8f, 8f..14f, 0.95f, 0.45f..0.8f, 0.15f),
        RainLayer(10f, 10f, 10f, 10f..18f, 0.6f, 0.6f..0.95f, 0.35f),
        RainLayer(13f, 14f, 13f, 12f..22f, 0.2f, 0.8f..1f, 0.6f),
    )

    /** How long a glyph takes to fade to about 2% (s). */
    const val TRAIL_SECONDS = 12f
    /** How often each pixel is faded (s): fewer, bigger steps leave no 8-bit ghosts. */
    const val FADE_INTERVAL = 0.2f
    /** The fade is spread over this many interleaved stripe sets, one set at a time. */
    const val FADE_PHASES = 12
    /** A fade stripe's height (device px). */
    const val FADE_STRIPE = 2
    /** Where drops die: depth = h · (1 − r^END_BIAS). */
    const val END_BIAS = 5f
    /** Pulls body colours toward grey (the reference's muted green). */
    const val BODY_DESATURATE = 0.5f
    /** How far a glow reaches past its cell, per dp of blur (plus 1 dp). */
    const val GLOW_REACH = 0.9f
    /** Share of a trail re-rolled a second (the flicker). */
    const val MUTATIONS_PER_SECOND = 1.2f
    /** A drop waits this long (s, range) at the top before falling again. */
    val RESPAWN_DELAY = 0.2f..4f
    /** Seconds simulated before the first frame, so the screen opens full. */
    const val PREWARM_SECONDS = 18f
    /** The pre-warm's simulation step (s). */
    const val PREWARM_STEP = 1f / 20f
    /** The pre-warm paints a glyph only if the fades would have left it this bright. */
    const val PREWARM_MIN_ALPHA = 0.05f
    /** Glyphs this bright and up are painted first (the rest after: same picture). */
    const val PREWARM_REVEAL_ALPHA = 0.25f
    /** A head's leftover glow is painted only if still this strong. */
    const val PREWARM_HALO_MIN_ALPHA = 0.15f
    /** The longest real step taken in one frame (s). */
    const val MAX_FRAME_STEP = 0.1f

    /** The glow levels ('low' is the look before cyberdeck_terminal 0.5; 'high' the default). */
    val GLOW = mapOf(
        GlowLevel.LOW to RainGlow(0.4f, 5f, 1, 2f),
        GlowLevel.MEDIUM to RainGlow(0.65f, 7f, 2, 3f),
        GlowLevel.HIGH to RainGlow(0.8f, 9f, 2, 5f),
        GlowLevel.MAX to RainGlow(0.9f, 12f, 3, 6.5f),
    )

    /** The strength of one fade step: after TRAIL_SECONDS a glyph is down to 2%. */
    val FADE_ALPHA: Float = 1f - 0.02f.pow(FADE_INTERVAL / TRAIL_SECONDS)

    /** The order stripe sets are faded in (stepping by 7 scatters them: no sweeping line). */
    val FADE_ORDER: IntArray = IntArray(FADE_PHASES) { (it * 7) % FADE_PHASES }

    /** A glyph's opacity [seconds] after it was drawn at [alpha] (what the fades have left). */
    fun fadedAlpha(alpha: Float, seconds: Float): Float = alpha * (1f - FADE_ALPHA).pow(floor(seconds / FADE_INTERVAL))
}
