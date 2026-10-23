/*
 * gl/Gl.kt: the small GL helpers the scenes share: programs built from the
 * shader files in assets/shaders, a reusable instance buffer, the
 * pixel-to-clip conventions.
 *
 * Layer: gl (GLES 3.0). Shaders live in assets/shaders as GLSL ES 3.00
 * files, not strings in code, so tools/shader-check can compile and run the
 * very same files in WebGL2 (the same language) on a machine with no phone.
 *
 * Conventions every shader follows: positions are render-target pixels,
 * y down from the top (uTarget = width, height); colours are premultiplied
 * by alpha; nothing allocates per frame (buffers here are made once and
 * reused).
 */
package com.thedungeons2077.fluidmotion.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Logcat tag for the GL layer. */
const val GL_TAG = "fluid_motion.gl"

/** A linked program: its handle, and its uniforms looked up by name once. */
class GlProgram(assets: AssetManager, vertex: String, fragment: String) {
    /** The program handle. */
    val id: Int

    private val locations = HashMap<String, Int>()

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, source(assets, vertex), vertex)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, source(assets, fragment), fragment)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        // The shaders are part of the program now: flag them for deletion with it.
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            GLES30.glDeleteProgram(id)
            error("link $vertex + $fragment: $log")
        }
    }

    /** A uniform's location (-1 if the shader doesn't use it: GL then ignores sets to it). */
    fun u(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    /** Makes it the current program. */
    fun use() = GLES30.glUseProgram(id)

    /** Frees it (context current). */
    fun release() = GLES30.glDeleteProgram(id)

    private companion object {
        /** Shader sources, read once per process (several loops may build the same program). */
        val cache = HashMap<String, String>()

        /**
         * A shader's source, its `#include name` lines replaced by
         * shaders/name.glsl (GLSL ES has no includes; shared helpers live in
         * .glsl files, which tools/shader-check pastes in the same way).
         */
        fun source(assets: AssetManager, name: String): String = synchronized(cache) {
            cache.getOrPut(name) {
                assets.open("shaders/$name").bufferedReader().use { it.readText() }.lines().joinToString("\n") { line ->
                    val include = line.trim().removePrefix("#include ").takeIf { line.trim().startsWith("#include ") }
                    if (include != null) source(assets, "$include.glsl") else line
                }
            }
        }

        fun compile(type: Int, src: String, name: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, src)
            GLES30.glCompileShader(shader)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                error("compile $name: $log")
            }
            return shader
        }
    }
}

/**
 * A stream of per-instance floats, [stride] floats each, up to [capacity]
 * instances: filled on the CPU each frame, then sent and drawn. The
 * FloatBuffer and the GL buffer are made once; a send replaces the GL
 * buffer's storage (glBufferData), which lets the driver hand over fresh
 * memory instead of waiting for the GPU to finish with the last frame's.
 */
class InstanceStream(val stride: Int, val capacity: Int) {
    /** The CPU side: put floats here, [stride] per instance. */
    val data: FloatBuffer = ByteBuffer.allocateDirect(stride * capacity * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** The GL buffer and the vertex array that reads it. */
    val buffer: Int
    val vao: Int

    /** Instances put since the last send. */
    var count = 0
        private set

    init {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        buffer = ids[0]
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
    }

    /** Declares attribute [location] as [size] floats at float [offset] of each instance (call once, after init). */
    fun attribute(location: Int, size: Int, offset: Int) {
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glEnableVertexAttribArray(location)
        GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT, false, stride * 4, offset * 4)
        // One value per instance, not per vertex: corners come from gl_VertexID.
        GLES30.glVertexAttribDivisor(location, 1)
        GLES30.glBindVertexArray(0)
    }

    /** Room for one more instance? */
    val full: Boolean get() = count >= capacity

    /** Starts an instance: returns the buffer positioned at it; put exactly [stride] floats. */
    fun next(): FloatBuffer {
        data.position(count * stride)
        count++
        return data
    }

    /** Sends what was put and draws it as quads (4-vertex strips), then starts over. */
    fun drawQuads() {
        if (count == 0) return
        data.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * stride * 4, data, GLES30.GL_STREAM_DRAW)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)
        GLES30.glBindVertexArray(0)
        count = 0
    }

    /** Forgets what was put (nothing drawn). */
    fun clear() {
        count = 0
    }

    /** Frees the GL side (context current). */
    fun release() {
        GLES30.glDeleteBuffers(1, intArrayOf(buffer), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
    }
}

/**
 * A vertex array with no attributes, for shaders that build their corners
 * from gl_VertexID and read the rest from uniforms (full-screen passes,
 * uniform-array instances). ES 3 wants one bound to draw.
 */
class EmptyVao {
    val id: Int = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]

    /** Draws [instances] quads (4-vertex strips). */
    fun drawQuads(instances: Int = 1) {
        if (instances <= 0) return
        GLES30.glBindVertexArray(id)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, instances)
        GLES30.glBindVertexArray(0)
    }

    /** Draws one triangle covering the target (full-screen passes). */
    fun drawFullScreen() {
        GLES30.glBindVertexArray(id)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    fun release() = GLES30.glDeleteVertexArrays(1, intArrayOf(id), 0)
}

/** Sets a vec3 uniform from 0xRRGGBB (0–1 floats). */
fun uniformRgb(location: Int, rgb: Int) {
    GLES30.glUniform3f(location, ((rgb shr 16) and 255) / 255f, ((rgb shr 8) and 255) / 255f, (rgb and 255) / 255f)
}

/** Logs a pending GL error, tagged [what]: for setup steps (it stalls the pipeline, so never per frame). */
fun checkGl(what: String) {
    val e = GLES30.glGetError()
    if (e != GLES30.GL_NO_ERROR) Log.w(GL_TAG, "$what: GL error 0x${Integer.toHexString(e)}")
}
