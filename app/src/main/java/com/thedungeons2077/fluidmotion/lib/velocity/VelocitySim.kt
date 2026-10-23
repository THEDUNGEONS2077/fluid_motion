/*
 * lib/velocity/VelocitySim.kt: VELOCITY's scene and motion (light caught in
 * motion), a port of cyberdeck_terminal's (src/wallpaper/velocity/), which
 * was matched to its reference (wallhaven-ox7v2l.jpg).
 *
 * Layer: lib (pure Kotlin). Soft slanted panels of light (one bright pale
 * one, a few dim red ones) drift across at different speeds and come round
 * again with new shapes; thin streaks race past, mostly near the top; long
 * faint lines flicker. The renderer (wallpaper/velocity/VelocityRenderer.kt)
 * draws it additively, with the corner flare.
 *
 * Sizes and speeds are shares of a base length, ¾ of the screen's longer
 * side: the reference is landscape, and shares of a portrait phone's narrow
 * width would make thin slivers of its panels. Leans are shares of the
 * width a 16:9 screen this tall would have, so the panels slant at the
 * reference's angle on any screen; the flare is placed from the bottom-right
 * corner in base lengths, so its arc keeps clear of the screen's middle.
 * On a 16:9 landscape screen all of it is the web's exactly.
 */
package com.thedungeons2077.fluidmotion.lib.velocity

import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import com.thedungeons2077.fluidmotion.lib.rain.Span
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt

/** A panel's look: width and drift speed (shares of the base length, speed a second), lean (top's offset right of its bottom, a share of the 16:9 width), brightness. */
data class PanelKind(
    val width: Span,
    val lean: Span,
    val alpha: Span,
    val speed: Span,
)

/** VELOCITY's numbers. */
object VelocityParams {
    /** The pale panel (one at a time, like the reference's). */
    val PALE = PanelKind(Span(0.2f, 0.26f), Span(0.11f, 0.16f), Span(0.85f, 1f), Span(0.0035f, 0.006f))
    /** The red panels. */
    val RED = PanelKind(Span(0.07f, 0.2f), Span(0f, 0.05f), Span(0.1f, 0.22f), Span(0.006f, 0.014f))
    const val RED_PANELS = 3
    /** A panel's edges fade over this share of its width. */
    const val SOFT_EDGE = 0.18f
    /** The fine motion-blur lines' strength. */
    const val STRIATION = 0.35f
    /** Streaks: new ones a second, speed, tail length (shares of the base), thickness (dp). */
    const val STREAKS_PER_SECOND = 4.5f
    val STREAK_SPEED = Span(0.35f, 1.1f)
    val STREAK_LENGTH = Span(0.12f, 0.45f)
    val STREAK_WIDTH = Span(0.8f, 2.2f)
    /** Share of streaks in the pale colour (the rest red). */
    const val PALE_STREAKS = 0.2f
    /** Long faint lines across the screen, and their opacity range. */
    const val LONG_LINES = 10
    val LONG_LINE_ALPHA = Span(0.05f, 0.22f)
    /** Streaks in flight at most (a fixed pool: a phone has about 14 at once). */
    const val MAX_STREAKS = 48

    /** The reference's shape: leans are shares of a screen this wide for its height. */
    const val REF_ASPECT = 16f / 9f

    /** The corner flare, in base lengths: its inset from the bottom-right corner, and the bloom's radius. */
    const val FLARE_INSET_X = 0.0067f
    const val FLARE_INSET_Y = 0.09f
    const val FLARE_RADIUS = 0.227f
    /** The flare's arc of light: its centre's offset from the flare and its radius (base lengths), its span (radians, clockwise from +x, as screen y runs down), its thickness (dp) and brightness. */
    const val ARC_DX = -0.3667f
    const val ARC_DY = -0.285f
    const val ARC_RADIUS = 0.48f
    const val ARC_FROM = (0.08 * PI).toFloat()
    const val ARC_TO = (0.4 * PI).toFloat()
    const val ARC_WIDTH = 4f
    const val ARC_ALPHA = 0.45f

    /** How fast the panels and the flare breathe (radians a second). */
    const val PANEL_BREATH = 0.4f
    const val FLARE_BREATH = 0.25f

    /** The glow levels: the light's strength. */
    val GLOW = mapOf(GlowLevel.LOW to 0.75f, GlowLevel.MEDIUM to 0.9f, GlowLevel.HIGH to 1f, GlowLevel.MAX to 1.18f)
}

/** The scene's colours (0xRRGGBB): the pale light, the red light, the flare. */
data class VelocityTones(val pale: Int, val red: Int, val flare: Int)

/** VELOCITY's colours from a palette: the pale light leans blue (the reference's is rgb(190, 226, 252)), the cool hue lifted with its blue kept up; the red is the accent; the flare the cool hue lifted less. */
fun velocityTones(c: PaletteColors): VelocityTones {
    fun lift(rgb: Int, shift: Int, t: Float): Int {
        val v = (rgb shr shift) and 255
        return (v + (255 - v) * t).roundToInt() shl shift
    }
    val pale = lift(c.cool, 16, 0.62f) or lift(c.cool, 8, 0.62f) or lift(c.cool, 0, 0.92f)
    val flare = lift(c.cool, 16, 0.35f) or lift(c.cool, 8, 0.35f) or lift(c.cool, 0, 0.35f)
    return VelocityTones(pale, c.accent, flare)
}

/**
 * VELOCITY on a [w] × [h] dp screen, at [zoom] (lib/Zoom.kt): laid out in
 * shares of the screen, so zoomed it isn't laid out smaller but grown: its
 * panels, streaks and flare are shares of a zoomed base length; the flare
 * stays in its corner, and the panels keep their slant.
 */
class VelocitySim(val w: Float, val h: Float, private val rng: Rng, zoom: Float = 1f) {
    /** The screen's base length (dp): ¾ of its longer side; the flare is placed in it. */
    private val layoutBase = maxOf(w, h) * 0.75f

    /** The base length the sizes and speeds are shares of (dp): the screen's, zoomed. */
    val base = layoutBase * zoom
    /** The length leans are shares of (dp): a 16:9 screen's width at this height. */
    private val slant = h * VelocityParams.REF_ASPECT

    /** The corner flare (dp): the bloom's centre and radius, its arc's centre and radius. */
    val flareX = w - VelocityParams.FLARE_INSET_X * layoutBase
    val flareY = h - VelocityParams.FLARE_INSET_Y * layoutBase
    val flareR = VelocityParams.FLARE_RADIUS * base
    val arcX = flareX + VelocityParams.ARC_DX * base
    val arcY = flareY + VelocityParams.ARC_DY * base
    val arcR = VelocityParams.ARC_RADIUS * base

    /** Panels: 0 is the pale one; bottom-left x, width, lean, brightness, speed, breathing phase. */
    val panels = 1 + VelocityParams.RED_PANELS
    val px = FloatArray(panels)
    val pw = FloatArray(panels)
    val lean = FloatArray(panels)
    val pa = FloatArray(panels)
    private val speed = FloatArray(panels)
    val phase = FloatArray(panels)

    /** Streaks (a fixed pool): head x, y, speed, tail length, thickness, pale or red, brightness. */
    val sx = FloatArray(VelocityParams.MAX_STREAKS)
    val sy = FloatArray(VelocityParams.MAX_STREAKS)
    private val sv = FloatArray(VelocityParams.MAX_STREAKS)
    val sl = FloatArray(VelocityParams.MAX_STREAKS)
    val sw = FloatArray(VelocityParams.MAX_STREAKS)
    val sPale = BooleanArray(VelocityParams.MAX_STREAKS)
    val sa = FloatArray(VelocityParams.MAX_STREAKS)
    var streaks = 0
        private set
    private var streakDebt = 0f

    /** Long lines: height, brightness, flicker rate and phase. */
    val ly = FloatArray(VelocityParams.LONG_LINES)
    val la = FloatArray(VelocityParams.LONG_LINES)
    val lRate = FloatArray(VelocityParams.LONG_LINES)
    val lPhase = FloatArray(VelocityParams.LONG_LINES)

    /** The flare's breathing phase (radians). */
    var flarePhase = 0f
        private set

    init {
        // The pale panel near the left edge, as the reference's.
        newPanel(0, anywhere = true)
        px[0] = w * (-0.03f + rng.next() * 0.06f)
        for (i in 1 until panels) {
            newPanel(i, anywhere = true)
            val jitter = rng.next()
            px[i] = if (w >= h) {
                // Landscape, as the web: clear of the pale panel, spread over the rest (the first mid-screen, as the reference's band).
                w * (0.42f + ((i - 1) * 0.55f) / VelocityParams.RED_PANELS + jitter * 0.06f)
            } else {
                // Portrait: the pale panel slants right across, so the red ones start spread over the whole width,
                // one in each third (as they'd be once they've come round a few times): not bunched on the
                // right, where they'd all drift off together in the first minute.
                w * ((i - 0.5f) / VelocityParams.RED_PANELS + (jitter - 0.5f) * 0.1f) - pw[i] / 2f
            }
        }
        for (i in 0 until VelocityParams.LONG_LINES) {
            ly[i] = h * rng.next().pow(1.5f)
            la[i] = rng.between(VelocityParams.LONG_LINE_ALPHA)
            lRate[i] = 0.3f + rng.next() * 1.2f
            lPhase[i] = rng.next() * 6.28f
        }
    }

    /** A new shape for panel [i]; [anywhere] places it on screen, else just off the left edge. */
    private fun newPanel(i: Int, anywhere: Boolean) {
        val kind = if (i == 0) VelocityParams.PALE else VelocityParams.RED
        pw[i] = rng.between(kind.width) * base
        lean[i] = rng.between(kind.lean) * slant
        pa[i] = rng.between(kind.alpha)
        speed[i] = rng.between(kind.speed) * base
        phase[i] = rng.next() * 6.28f
        px[i] = if (anywhere) rng.next() * w else -pw[i] - lean[i]
    }

    /** Moves everything on by [dt] seconds. */
    fun advance(dt: Float) {
        // The slow cycles' phases, each kept in one turn (no clock goes into a sine: smooth for months).
        flarePhase = (flarePhase + VelocityParams.FLARE_BREATH * dt) % TAU
        for (i in 0 until VelocityParams.LONG_LINES) lPhase[i] = (lPhase[i] + lRate[i] * dt) % TAU
        for (i in 0 until panels) {
            phase[i] = (phase[i] + VelocityParams.PANEL_BREATH * dt) % TAU
            px[i] += speed[i] * dt
            // Gone off the right: round again from the left, a new shape.
            if (px[i] - lean[i] > w + pw[i]) newPanel(i, anywhere = false)
        }
        streakDebt += VelocityParams.STREAKS_PER_SECOND * dt
        while (streakDebt >= 1f) {
            streakDebt -= 1f
            if (streaks >= VelocityParams.MAX_STREAKS) continue
            val i = streaks++
            sx[i] = 0f
            // More streaks near the top, as the reference.
            sy[i] = h * minOf(1f, rng.next().pow(2.6f))
            sv[i] = rng.between(VelocityParams.STREAK_SPEED) * base
            sl[i] = rng.between(VelocityParams.STREAK_LENGTH) * base
            sw[i] = rng.between(VelocityParams.STREAK_WIDTH)
            sPale[i] = rng.next() < VelocityParams.PALE_STREAKS
            sa[i] = 0.35f + rng.next() * 0.6f
        }
        var i = 0
        while (i < streaks) {
            sx[i] += sv[i] * dt
            if (sx[i] - sl[i] < w) {
                i++
                continue
            }
            // Gone past the right edge: the last one takes its place.
            streaks--
            sx[i] = sx[streaks]; sy[i] = sy[streaks]; sv[i] = sv[streaks]; sl[i] = sl[streaks]
            sw[i] = sw[streaks]; sPale[i] = sPale[streaks]; sa[i] = sa[streaks]
        }
    }

    private companion object {
        /** A full turn (radians). */
        const val TAU = (2 * PI).toFloat()
    }
}
