/*
 * PalettesTest: the eight palettes are cyberdeck_terminal's, and every text
 * colour reads (WCAG AA) where the app draws it. A new palette must pass.
 */
package com.thedungeons2077.fluidmotion.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PalettesTest {
    @Test
    fun `palettes are the eight, phosphor first, ids unique`() {
        assertEquals(listOf("phosphor", "amber", "ice", "neon", "blood", "azure", "synth", "outrun"), Palettes.ALL.map { it.id })
        assertEquals("phosphor", Palettes.find("from-the-future").id)
        assertEquals("blood", Palettes.find("blood").id)
        assertEquals("phosphor", Palettes.find(null).id)
    }

    @Test
    fun `contrast is worked out as WCAG does`() {
        assertEquals(21.0, contrast(0x000000, 0xffffff), 1e-6)
        assertEquals(1.0, contrast(0x777777, 0x777777), 1e-6)
    }

    @Test
    fun `every text colour passes WCAG AA where it is drawn`() {
        for (p in Palettes.ALL) {
            val c = p.colors
            for (text in listOf(c.textDim, c.text, c.accent, c.textBright)) {
                for (bg in listOf(c.bg, c.surface, c.surfaceRaised)) {
                    assertTrue("${p.id}: ${text.toString(16)} on ${bg.toString(16)}", contrast(text, bg) >= 4.5)
                }
            }
            // Bright text on a selected thing's wash (the lit choice in a row of choices).
            assertTrue("${p.id}: bright on wash", contrast(c.textBright, c.wash) >= 4.5)
        }
    }

    @Test
    fun `mixing runs from one colour to the other`() {
        assertEquals(0x102030, mixRgb(0x102030, 0xffffff, 0f))
        assertEquals(0xffffff, mixRgb(0x102030, 0xffffff, 1f))
        assertEquals(0x808080, mixRgb(0x000000, 0xffffff, 0.5f))
        val f = rgbFloats(0xff8000)
        assertEquals(1f, f[0], 1e-6f)
        assertEquals(128f / 255f, f[1], 1e-6f)
        assertEquals(0f, f[2], 1e-6f)
    }
}
