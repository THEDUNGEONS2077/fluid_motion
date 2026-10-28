/*
 * ShaderUniformsTest: every uniform a renderer sets is declared in its
 * shaders, and every uniform its shaders declare is set. GL quietly ignores
 * a misspelt name and leaves an unset uniform at 0 (a scene collapsed to a
 * point, say), so without a phone this is where such slips are caught.
 *
 * Reads the sources: the renderers' (and gl/ScaledTarget.kt's) `val x =
 * GlProgram(context.assets, "a.vert", "b.frag")` and their `x.u("name")`
 * calls (a `for (p in listOf(a, b))` loop counts for each program listed, as
 * does `for (p in list)` over a `val list = listOf(a, b)`).
 */
package com.thedungeons2077.fluidmotion.scene

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShaderUniformsTest {
    private val main = File("src/main")
    private val shaders = File(main, "assets/shaders")

    /** A shader's source with its includes pasted in (as gl/Gl.kt does). */
    private fun source(name: String): String = File(shaders, name).readLines().joinToString("\n") { line ->
        val t = line.trim()
        if (t.startsWith("#include ")) source(t.removePrefix("#include ") + ".glsl") else line
    }

    /** The uniforms a source declares (comments ignored). */
    private fun declared(src: String): Set<String> {
        val code = src.lines().joinToString("\n") { it.substringBefore("//") }
        return Regex("""uniform\s+(?:(?:lowp|mediump|highp)\s+)?\w+\s+(\w+)\s*(?:\[[^\]]*\])?\s*;""").findAll(code).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `renderers and shaders agree on every uniform`() {
        val renderers = File(main, "java/com/thedungeons2077/fluidmotion/scene").walk().filter { it.name.endsWith("Renderer.kt") }.toList() +
            File(main, "java/com/thedungeons2077/fluidmotion/gl/ScaledTarget.kt")
        assertTrue("found the renderers", renderers.size == 9)
        val problems = mutableListOf<String>()
        var programCount = 0
        var uniformCount = 0
        for (file in renderers) {
            val code = file.readText()
            val programs = Regex("""val (\w+) = GlProgram\((?:context\.)?assets, "([\w.]+)", "([\w.]+)"\)""").findAll(code)
                .associate { it.groupValues[1] to (it.groupValues[2] to it.groupValues[3]) }
            // Lists of programs made once (`val list = listOf(a, b)`), and loops over a list written out or named.
            val lists = Regex("""val (\w+) = listOf\(([\w, ]+)\)""").findAll(code)
                .associate { m -> m.groupValues[1] to m.groupValues[2].split(",").map { it.trim() } }
            val loops = Regex("""for \((\w+) in listOf\(([\w, ]+)\)\)""").findAll(code)
                .associate { m -> m.groupValues[1] to m.groupValues[2].split(",").map { it.trim() } } +
                Regex("""for \((\w+) in (\w+)\)""").findAll(code).mapNotNull { m -> lists[m.groupValues[2]]?.let { m.groupValues[1] to it } }
            val used = programs.keys.associateWith { mutableSetOf<String>() }
            for (m in Regex("""(\w+)\.u\("(\w+)"\)""").findAll(code)) {
                val owner = m.groupValues[1]
                val targets = loops[owner] ?: listOf(owner)
                for (t in targets) used[t]?.add(m.groupValues[2]) ?: problems.add("${file.name}: ${m.value} on an unknown program")
            }
            programCount += programs.size
            for ((name, pair) in programs) {
                val decl = declared(source(pair.first)) + declared(source(pair.second))
                uniformCount += decl.size
                for (u in used.getValue(name) - decl) problems.add("${file.name}: $name sets $u, which ${pair.first}/${pair.second} don't declare")
                for (u in decl - used.getValue(name)) problems.add("${file.name}: ${pair.first}/${pair.second} declare $u, which $name never sets")
            }
        }
        println("programs $programCount, uniforms $uniformCount")
        // Not vacuous: rain 3 programs, neural 3, velocity 4, data flow 4, megacity 5 (its city cache's copy too), horizon 2, sunset 2, prism 3 (its lighter pair too), the upscale 1, and their uniforms all looked at.
        assertTrue("programs found: $programCount", programCount == 27)
        assertTrue("uniforms checked: $uniformCount", uniformCount > 120)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
