/*
 * PrismLiteTest: PRISM's lighter shaders for LOW and MEDIUM (prism_lite.*)
 * and their finer cube (lib/prism/Prism.kt gridCubeVertices). The cube is
 * the web's cube cut into a grid, the same faces in the same order and
 * winding; prism_lite.frag is pr_box.frag (the web's, byte for byte) but for
 * the light pools' sum, which moves to the vertex shader, so the two can't
 * drift apart; HIGH and ULTRA keep the web's shaders.
 */
package com.thedungeons2077.fluidmotion.lib.prism

import com.thedungeons2077.fluidmotion.lib.Quality
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class PrismLiteTest {
    private val shaders = File("src/main/assets/shaders")

    @Test
    fun `only LOW and MEDIUM draw with the lighter shaders`() {
        assertEquals(listOf(true, true, false, false), Quality.entries.map { PrismParams.lite(it) })
    }

    @Test
    fun `the finer cube is the web's cube cut into a grid`() {
        // One cell a face: the web's cube exactly.
        assertArrayEquals(cubeVertices(), gridCubeVertices(1), 0f)
        val n = PrismParams.LITE_GRID
        val grid = gridCubeVertices(n)
        val cube = cubeVertices()
        assertEquals(6 * n * n * 6 * 6, grid.size)
        for (f in 0 until 6) {
            val normal = cube.copyOfRange(f * 36 + 3, f * 36 + 6)
            // The face's own normal on every vertex, every corner on the face's plane, inside the unit square.
            val axis = normal.indexOfFirst { it != 0f }
            val plane = if (normal[axis] > 0f) 1f else 0f
            var area = 0.0
            for (t in 0 until 2 * n * n) {
                val o = (f * 6 * n * n + t * 3) * 6
                val p = Array(3) { k -> grid.copyOfRange(o + k * 6, o + k * 6 + 3) }
                for (k in 0 until 3) {
                    assertArrayEquals(normal, grid.copyOfRange(o + k * 6 + 3, o + k * 6 + 6), 0f)
                    assertEquals(plane, p[k][axis], 1e-6f)
                    assertTrue(p[k].all { it >= -1e-6f && it <= 1f + 1e-6f })
                }
                // Wound as the face is (counter-clockwise from outside): the cross product points along the normal.
                val a = FloatArray(3) { p[1][it] - p[0][it] }
                val b = FloatArray(3) { p[2][it] - p[0][it] }
                val cross = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
                assertTrue("face $f, triangle $t wound the wrong way", cross[axis] * normal[axis] > 0f)
                area += abs(cross[axis]) / 2.0
            }
            // The cells tile the face: a unit square, no gaps, no overlaps.
            assertEquals(1.0, area, 1e-4)
        }
    }

    /** A shader's code from the line after the light pools' sum to the end. */
    private fun afterTheSum(name: String, from: String): String {
        val src = File(shaders, name).readText()
        val main = src.substring(src.indexOf("void main()"))
        return main.substring(main.indexOf(from))
    }

    @Test
    fun `prism_lite matches the web's shaders but for where the light pools are summed`() {
        val frag = File(shaders, "pr_box.frag").readText()
        val lite = File(shaders, "prism_lite.frag").readText()
        // From the pools' sum on, the fragment shaders are the same code.
        assertEquals(afterTheSum("pr_box.frag", "    vec3 v = normalize"), afterTheSum("prism_lite.frag", "    vec3 v = normalize"))
        // Declarations: the web's, the lights' uniforms traded for the sum handed in.
        val decl = { s: String -> s.substring(s.indexOf("precision highp float;"), s.indexOf("void main()")) }
        val lights = "const int MAX_LIGHTS = 16;\nuniform vec4 uLightPos[MAX_LIGHTS];  // xyz, reach (units)\nuniform vec3 uLightCol[MAX_LIGHTS];  // colour × strength × breath\nuniform int uLights;\n"
        assertEquals(
            decl(frag).replace(lights, "").replace("flat in vec3 vSize;\n", "flat in vec3 vSize;\nin vec3 vLight;  // the light pools' sum, from the grid's corners\n"),
            decl(lite),
        )
        // The vertex shader sums the pools exactly as the web's fragment shader does.
        val sum = frag.substring(frag.indexOf("    vec3 light = vec3(0.0);"), frag.indexOf("    vec3 v = normalize"))
        assertTrue(File(shaders, "prism_lite.vert").readText().contains(sum))
        assertFalse("the web's shaders don't know the grid's sum", frag.contains("vLight"))
    }
}
