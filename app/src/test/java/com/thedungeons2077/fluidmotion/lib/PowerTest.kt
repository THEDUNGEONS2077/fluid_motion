/*
 * PowerTest: the battery rules: the profile's rate, frozen in battery saver
 * and when hot, halved when warm; frames paced one interval apart.
 */
package com.thedungeons2077.fluidmotion.lib

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerTest {
    @Test
    fun `the profile sets the rate when nothing else does`() {
        assertEquals(30, frameRate(PowerChoice(), PowerState()))
        assertEquals(20, frameRate(PowerChoice(PowerProfile.ECO), PowerState()))
        assertEquals(60, frameRate(PowerChoice(PowerProfile.SMOOTH), PowerState()))
        assertEquals(PowerProfile.BALANCED, PowerProfile.find("nonsense"))
        assertEquals(PowerProfile.ECO, PowerProfile.find("ECO"))
    }

    @Test
    fun `battery saver freezes it, unless the user said not to`() {
        assertEquals(0, frameRate(PowerChoice(), PowerState(powerSave = true)))
        assertEquals(30, frameRate(PowerChoice(stillInSaver = false), PowerState(powerSave = true)))
    }

    @Test
    fun `a warm phone halves the rate, a hot one freezes it`() {
        assertEquals(30, frameRate(PowerChoice(), PowerState(thermal = Thermal.LIGHT)))
        assertEquals(15, frameRate(PowerChoice(), PowerState(thermal = Thermal.MODERATE)))
        assertEquals(10, frameRate(PowerChoice(PowerProfile.ECO), PowerState(thermal = Thermal.MODERATE)))
        assertEquals(30, frameRate(PowerChoice(PowerProfile.SMOOTH), PowerState(thermal = Thermal.MODERATE)))
        assertEquals(0, frameRate(PowerChoice(), PowerState(thermal = Thermal.SEVERE)))
        assertEquals(0, frameRate(PowerChoice(), PowerState(thermal = 6)))
        // The user can turn the cooling off.
        assertEquals(60, frameRate(PowerChoice(PowerProfile.SMOOTH, coolWhenHot = false), PowerState(thermal = Thermal.SEVERE)))
    }

    @Test
    fun `every rate lands on even vsync steps at 60 and 120 Hz`() {
        for (profile in PowerProfile.entries) {
            for (thermal in listOf(Thermal.NONE, Thermal.MODERATE)) {
                val fps = frameRate(PowerChoice(profile), PowerState(thermal = thermal))
                assertEquals("$profile at $thermal: $fps", 0, 120 % fps)
                assertEquals("$profile at $thermal: $fps", 0, 60 % fps)
            }
        }
    }

    @Test
    fun `frames are one interval apart, restarting after a pause`() {
        val second = 1_000_000_000L
        assertEquals(5L, nextFrameAt(0L, 5L, 30))
        assertEquals(second + second / 30, nextFrameAt(second, second + 1, 30))
        // Long past the next slot (the screen was off): now, not a burst of catch-up frames.
        assertEquals(10 * second, nextFrameAt(second, 10 * second, 30))
    }
}
