/*
 * lib/neural/NeuralFrame.kt: one frame of the neural net as the shaders
 * take it: the uniform arrays scene/neural/NeuralRenderer.kt sends (and the
 * JVM frame dump hands tools/shader-check, so both draw the same).
 *
 * Layer: lib (pure Kotlin). Arrays are made once by the caller and filled
 * each frame: no allocation.
 */
package com.thedungeons2077.fluidmotion.lib.neural

import kotlin.math.ceil
import kotlin.math.min

/** The neural net's per-frame packing. */
object NeuralFrame {
    /** The shaders' array sizes (neural_*.vert declare the same). */
    const val MAX_NODES = 128
    const val MAX_SPARKS = 160
    const val MAX_BOKEH = 32

    /** A link's glow pass is this many times as wide as the link. */
    const val GLOW_WIDTH = 4f

    /** A signal's sprite, in node core radii (the web's 2.2). */
    const val PULSE_RADIUS = 2.2f

    /**
     * The node sprite's full radius per dp of core radius: the web's sprite
     * size over its core, both whole device px at [density].
     */
    fun spriteScale(haloSize: Float, density: Float): Float {
        val core = ceil(4f * density)
        return ceil(core * haloSize) / core
    }

    /** Bokeh as vec4s (x, y, radius, opacity): returns how many. */
    fun bokeh(s: NeuralSim, out: FloatArray): Int {
        for (i in 0 until s.nb) {
            out[4 * i] = s.bx[i]
            out[4 * i + 1] = s.by[i]
            out[4 * i + 2] = s.br[i]
            out[4 * i + 3] = s.ba[i]
        }
        return s.nb
    }

    /** Node positions, two a vec4: returns how many vec4s. */
    fun nodes(s: NeuralSim, out: FloatArray): Int {
        for (i in 0 until s.n) {
            out[2 * i] = s.x[i]
            out[2 * i + 1] = s.y[i]
        }
        if (s.n % 2 == 1) {
            out[2 * s.n] = 0f
            out[2 * s.n + 1] = 0f
        }
        return (s.n + 1) / 2
    }

    /**
     * The sprites to draw, as vec4s (x, y, radius, opacity): the nodes on
     * screen (bigger and brighter while lit), then the signals (fading in
     * on their first link). Returns how many.
     */
    fun sparks(s: NeuralSim, haloSize: Float, density: Float, out: FloatArray): Int {
        val scale = spriteScale(haloSize, density)
        var k = 0
        for (i in 0 until s.n) {
            val r = s.radius[i] * (1 + 0.8f * s.flash[i]) * scale
            val x = s.x[i]
            val y = s.y[i]
            if (x < -r || y < -r || x > s.w + r || y > s.h + r) continue
            out[4 * k] = x
            out[4 * k + 1] = y
            out[4 * k + 2] = r
            out[4 * k + 3] = min(1f, 0.8f + 0.2f * s.flash[i])
            k++
        }
        for (i in 0 until s.pulses) {
            if (k >= MAX_SPARKS) break
            out[4 * k] = s.pulseX(i)
            out[4 * k + 1] = s.pulseY(i)
            out[4 * k + 2] = PULSE_RADIUS * scale
            out[4 * k + 3] = if (s.pulseFirst[i]) min(1f, s.pulseAlong[i] * 8f) else 1f
            k++
        }
        return k
    }
}
