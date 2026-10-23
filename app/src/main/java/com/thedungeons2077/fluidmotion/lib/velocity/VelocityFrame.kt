/*
 * lib/velocity/VelocityFrame.kt: one frame of VELOCITY as the shaders take
 * it: the uniform arrays scene/velocity/VelocityRenderer.kt sends (and the
 * JVM frame dump hands tools/shader-check, so both draw the same).
 *
 * Layer: lib (pure Kotlin). Arrays are made once by the caller and filled
 * each frame: no allocation.
 */
package com.thedungeons2077.fluidmotion.lib.velocity

import com.thedungeons2077.fluidmotion.lib.rain.Rng
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** VELOCITY's per-frame packing ([g]: the GLOW level's strength). */
object VelocityFrame {
    /** The striation tile's height (dp rows), as the web's pattern. */
    const val TILE = 96

    /** The fine motion-blur lines: one brightness (0–255) per dp row, mostly faint, now and then bright (the web's pattern). */
    fun striationTile(rng: Rng): ByteArray = ByteArray(TILE) {
        val v = if (rng.next() < 0.12f) 0.6f + rng.next() * 0.4f else rng.next() * 0.25f
        (v * 255f + 0.5f).toInt().toByte()
    }

    /** Panels as vec4s: bottom-left x, width, lean, opacity (breathing slowly). Returns how many. */
    fun panels(s: VelocitySim, g: Float, out: FloatArray): Int {
        for (i in 0 until s.panels) {
            out[4 * i] = s.px[i]
            out[4 * i + 1] = s.pw[i]
            out[4 * i + 2] = s.lean[i]
            out[4 * i + 3] = min(1f, s.pa[i] * g * (0.9f + 0.1f * sin(s.phase[i])))
        }
        return s.panels
    }

    /** The long lines as vec4s: y, opacity (flickering slowly). Returns how many. */
    fun lines(s: VelocitySim, g: Float, out: FloatArray): Int {
        for (i in 0 until VelocityParams.LONG_LINES) {
            out[4 * i] = s.ly[i]
            out[4 * i + 1] = s.la[i] * g * (0.55f + 0.45f * sin(s.lPhase[i]))
            out[4 * i + 2] = 0f
            out[4 * i + 3] = 0f
        }
        return VelocityParams.LONG_LINES
    }

    /** The streaks: [shapes] as vec4s (head x, y, tail length, thickness), [looks] (opacity, 1 if pale). Returns how many. */
    fun streaks(s: VelocitySim, shapes: FloatArray, looks: FloatArray): Int {
        for (i in 0 until s.streaks) {
            shapes[4 * i] = s.sx[i]
            shapes[4 * i + 1] = s.sy[i]
            shapes[4 * i + 2] = s.sl[i]
            shapes[4 * i + 3] = s.sw[i]
            looks[4 * i] = s.sa[i]
            looks[4 * i + 1] = if (s.sPale[i]) 1f else 0f
            looks[4 * i + 2] = 0f
            looks[4 * i + 3] = 0f
        }
        return s.streaks
    }

    /** The flare's strength now: the glow, breathing. */
    fun flareStrength(s: VelocitySim, g: Float): Float = g * (0.85f + 0.15f * sin(s.flarePhase))

    /**
     * The box round the flare's arc (dp: left, top, right, bottom): within
     * one quarter turn its ends bound it (inner and outer edge), plus a
     * pixel at [density] for the smooth edge.
     */
    fun arcBox(s: VelocitySim, density: Float, out: FloatArray) {
        val reach = VelocityParams.ARC_WIDTH / 2f + 1f / density
        val a0 = VelocityParams.ARC_FROM
        val a1 = VelocityParams.ARC_TO
        out[0] = s.arcX + (s.arcR - reach) * cos(a1)
        out[1] = s.arcY + (s.arcR - reach) * sin(a0)
        out[2] = s.arcX + (s.arcR + reach) * cos(a0)
        out[3] = s.arcY + (s.arcR + reach) * sin(a1)
    }
}
