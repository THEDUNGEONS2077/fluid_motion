/*
 * ZoomTest: ZOOM's steps (the web's: Chrome's levels from 50% to 200%),
 * snapping a stored value to the nearest, stepping in and out, and stopping
 * at the ends; and what a zoom does to the scenes that count per screen
 * (the neural net) or size by the screen (VELOCITY).
 */
package com.thedungeons2077.fluidmotion.lib

import com.thedungeons2077.fluidmotion.lib.neural.NeuralFrame
import com.thedungeons2077.fluidmotion.lib.neural.NeuralSim
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import com.thedungeons2077.fluidmotion.lib.velocity.VelocitySim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomTest {
    @Test
    fun `steps run from 50 to 200 percent, 100 among them, as the web's`() {
        assertEquals(listOf(0.5f, 0.67f, 0.75f, 0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f), Zoom.STEPS.toList())
        for (i in 1 until Zoom.STEPS.size) assertTrue(Zoom.STEPS[i] > Zoom.STEPS[i - 1])
        assertEquals(0.5f, Zoom.MIN, 0f)
        assertEquals(2f, Zoom.MAX, 0f)
    }

    @Test
    fun `a stored zoom snaps to the nearest step, and steps stop at the ends`() {
        assertEquals(1.25f, Zoom.nearest(1.3f), 0f)
        assertEquals(2f, Zoom.nearest(9f), 0f)
        assertEquals(0.5f, Zoom.nearest(0.1f), 0f)
        assertEquals(Zoom.DEFAULT, Zoom.nearest(Float.NaN), 0f)
        assertEquals(1.1f, Zoom.step(1f, 1), 0f)
        assertEquals(0.9f, Zoom.step(1f, -1), 0f)
        assertEquals(2f, Zoom.step(2f, 1), 0f)
        assertEquals(0.5f, Zoom.step(0.5f, -1), 0f)
        assertEquals(1.5f, Zoom.step(1.3f, 1), 0f)
        assertEquals("125%", Zoom.label(1.25f))
        assertEquals("67%", Zoom.label(0.67f))
    }

    // An S25 Ultra's screen (dp); a scene at zoom z is made for a screen 1/z as big.
    private val w = 384f
    private val h = 832f

    @Test
    fun `zoomed in the neural net shows fewer nodes, zoomed out more, within the shaders' arrays`() {
        val at100 = NeuralSim(w, h, Rng(1)).n
        val at200 = NeuralSim(w / 2, h / 2, Rng(1), 2f)
        val at50 = NeuralSim(w * 2, h * 2, Rng(1), 0.5f)
        assertEquals(at100 / 4f, at200.n.toFloat(), 1f)
        assertTrue(at200.nb in 4..at100)
        assertEquals(NeuralFrame.MAX_NODES, at50.n)
        assertTrue(at50.nb <= NeuralFrame.MAX_BOKEH)
    }

    @Test
    fun `zoomed VELOCITY grows its sizes on screen and keeps its flare in the corner`() {
        val z = 2f
        val at100 = VelocitySim(w, h, Rng(1))
        val at200 = VelocitySim(w / z, h / z, Rng(1), z)
        // On screen (drawn z times as large): the base length doubles, the flare's place stays.
        assertEquals(at100.base * z, at200.base * z, 1e-3f * at100.base)
        assertEquals(at100.flareX, at200.flareX * z, 1e-3f)
        assertEquals(at100.flareY, at200.flareY * z, 1e-3f)
        assertEquals(at100.flareR * z, at200.flareR * z, 1e-3f)
    }
}
