/*
 * lib/sunset/Sunset.kt: SUNSET's numbers (cyberdeck_terminal's
 * src/wallpaper/sunset/params.ts), its colours from a palette, and how a
 * screen frames it.
 *
 * Layer: lib (pure Kotlin, no Android or GL). SUNSET has no geometry: the
 * sky and the ground are worked out per pixel by the web's own shaders
 * (assets/shaders/ss_*). All a phone changes is the framing, through the
 * shaders' uniforms (SunsetView): what differs from the web, and why, is
 * marked PHONE.
 */
package com.thedungeons2077.fluidmotion.lib.sunset

import com.thedungeons2077.fluidmotion.lib.PaletteColors
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** SUNSET's numbers. */
object SunsetParams {
    /** The horizon (share of the screen's height from the top). */
    const val HORIZON = 0.61f

    /** The sun: its centre across (share of the width) and down (share of the web's height), its radius and its glow's reach (shares of the composition's scale). */
    const val SUN_X = 0.5f
    const val SUN_Y = 0.48f
    const val SUN_R = 0.17f
    const val SUN_GLOW = 0.05f

    /** The sun's stripes: where the gaps start, how often they come, and the first and last gap's share of a band (sun radii). */
    const val STRIPE_FROM = -0.32f
    const val STRIPE_PERIOD = 0.15f
    const val GAP_FIRST = 0.1f
    const val GAP_LAST = 0.5f

    /** The camera over the grid: its height (units), its focal length (dp per unit at distance 1, on the web's 1080-tall layout). */
    const val CAMERA_HEIGHT = 1f
    const val FOCAL = 1080f

    /** The grid's lines: width and glow (dp). */
    const val LINE = 1f
    const val LINE_GLOW = 3f

    /** How the ground waves: how much the lines bend (cells), and how often (cycles over WRAP units). */
    const val WAVE_AMOUNT = 0.32f
    const val WAVE_ACROSS = 9f
    const val WAVE_ALONG = 13f

    /** How fast the grid glides toward you (units a second, at the web's camera). */
    const val SPEED = 0.6f

    /** The grid's distance wraps after this many units: whole squares and whole waves, so it carries on seamlessly. */
    const val WRAP = 240f

    /** How far below the horizon the ground melts into the sky (share of the composition's scale). */
    const val MELT = 0.05f

    /** The stars: a cell in so many dp, the share of cells with a star. */
    const val STAR_CELL = 3f
    const val STAR_CHANCE = 0.006f

    /** The scanlines: a dark row every so many dp, and how dark. */
    const val SCAN_EVERY = 3f
    const val SCAN_DEPTH = 0.16f

    /** PHONE: the screen shape the web's layout was made for (16:9). */
    const val REFERENCE_ASPECT = 16f / 9f

    /** The longest step the motion takes in one frame (s). */
    const val MAX_FRAME_STEP = 0.1f

    /** The waves' angular rates (radians a unit): whole cycles over WRAP, so the distance can wrap without a jump. */
    val WAVE_K: Float = (2 * PI / WRAP).toFloat()
}

/** SUNSET's colours (r, g, b each 0–1), from a palette (engine.ts sunsetTones; OUTRUN, made for it, gives the reference's). */
class SunsetTones(colors: PaletteColors) {
    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
    private fun mix(a: FloatArray, b: FloatArray, k: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * k }
    private val white = floatArrayOf(1f, 1f, 1f)
    private val bg = rgb(colors.bg)
    private val accent = rgb(colors.accent)
    private val cool = rgb(colors.cool)
    private val bright = rgb(colors.textBright)

    /** The night sky: purple on the left, blue on the right, darker at the top. */
    val skyLeft = mix(mix(accent, cool, 0.45f), bg, 0.7f)
    val skyRight = mix(cool, bg, 0.58f)
    val skyTop = mix(bg, cool, 0.06f)

    /** The sun, golden at its top (the brightest tint) to the accent at its base, and its glow. */
    val sunTop = bright
    val sunBase = accent
    val glow = accent

    /** The stars. */
    val star = mix(bright, white, 0.7f)

    /** The ground, and the grid: warm on the left, cool on the right. */
    val ground = mix(bg, mix(accent, cool, 0.6f), 0.1f)
    val warm = mix(accent, bright, 0.3f)
    val coolLine = mix(cool, white, 0.25f)
}

/**
 * How a screen frames SUNSET, from the target's size (px), its px per dp
 * and the zoom. On the web's 16:9 it's the web's own framing. PHONE: an
 * upright phone is far narrower, so
 *   - the composition (the sun, its glow, the melt) is scaled by the
 *     screen's short side, not its height, and the sun keeps its place on
 *     the horizon (its base cut by it, as in the reference);
 *   - the camera rises by the square root of how much narrower the screen
 *     is: the grid shows about half the web's squares across, bigger, so
 *     its perspective reads on a narrow screen (rising the full ratio, as
 *     many squares as the web's, crowded the far rows into a haze from far
 *     below the horizon), with the web's square shape, moving at the web's
 *     pace on screen.
 */
class SunsetView(val width: Float, val height: Float, val pxPerDp: Float, val zoom: Float) {
    private val p = SunsetParams

    /** How much narrower than 16:9 the screen is (1 at 16:9). */
    val narrow: Float = p.REFERENCE_ASPECT / (width / height)

    /** The composition's scale (px): the web's height, on its 16:9 (the short side), grown by the zoom. */
    val big: Float = min(width, height) * zoom

    /** The horizon (px from the top). */
    val horizon: Float = height * p.HORIZON

    /** The sun's centre (px from the top-left): as far above the horizon, in its scale, as on the web. */
    val sunX: Float = width * p.SUN_X
    val sunY: Float = horizon - (p.HORIZON - p.SUN_Y) * big

    /** The focal length (px): the web's, a share of its 1080-tall layout, so the height here; zoomed in, a narrower view. */
    val focal: Float = height * zoom

    /** The camera's height (units): risen by the square root of how much narrower the screen is (1 on 16:9). */
    val cameraHeight: Float = p.CAMERA_HEIGHT * sqrt(narrow)

    /** How fast the grid glides (units a second): the web's, times the camera's rise, so it moves on screen at the web's pace. */
    val speed: Float = p.SPEED * cameraHeight

    /**
     * The melt into the sky (px below the horizon). It must reach as far
     * down as the grid's lines crowd together and fade (else a dark band
     * shows between them), and lines crowd at a distance below the horizon
     * that grows as √(focal × camera height): on the web's 16:9 at 1 px per
     * CSS px this is its own MELT × 1080; an upright phone, denser and its
     * camera risen, needs about three times as many px.
     */
    val melt: Float = p.MELT * sqrt(big * focal * cameraHeight)
}

/** The grid's distance after [dt] seconds at [speed], wrapped at WRAP (whole squares and waves). */
fun advanceTravel(travel: Float, dt: Float, speed: Float): Float {
    val next = travel + min(dt, SunsetParams.MAX_FRAME_STEP) * speed
    return next - floor(next / SunsetParams.WRAP) * SunsetParams.WRAP
}
