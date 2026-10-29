/*
 * HorizonSkyTest: the phone's HORIZON sky (horizon_sky.frag) is the
 * dashboard's hz_sky.frag (byte for byte with cyberdeck_terminal, and left
 * as it is there) plus a dither against banding, nothing else; so the two
 * can't drift apart.
 */
package com.thedungeons2077.fluidmotion.lib.horizon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class HorizonSkyTest {
    private val shaders = File("src/main/assets/shaders")

    @Test
    fun `the phone's sky is the dashboard's plus its dither, and only that`() {
        val web = File(shaders, "hz_sky.frag").readText()
        val phone = File(shaders, "horizon_sky.frag").readText()
        val from = { s: String -> s.substring(s.indexOf("precision highp float;")) }
        val dither = "    // Up to half a shade either way, fixed per pixel: no rings, nothing that moves.\n" +
            "    float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));\n" +
            "    c += (n - 0.5) / 255.0;\n"
        assertEquals(from(web), from(phone).replace(dither, ""))
        assertFalse("the dashboard's sky isn't dithered", web.contains("gl_FragCoord.xy, vec2(0.06711056"))
    }
}
