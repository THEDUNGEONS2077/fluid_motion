/*
 * scene/horizon/HorizonRenderer.kt: HORIZON on the GPU: a wireframe land
 * running to a glowing horizon, the camera gliding forward over it, drawn
 * with cyberdeck_terminal's own shaders (hz_*: one set, byte for byte),
 * but for the sky: the phone's own horizon_sky.frag, hz_sky.frag plus a
 * dither against banding on an OLED (0.12.0; the dashboard's is untouched).
 *
 * Layer: scene (GL). One pass, four draw calls at most:
 *   1. the sky (horizon_sky.frag, hz_sky.frag dithered: the plum sky, the glow and its haze dome, the
 *      white core), over the top half only. The web draws it once and keeps
 *      it; a phone's screen doesn't keep its last picture, and reading a
 *      kept copy back every frame costs more battery (memory traffic) than
 *      working these few sums out again per pixel;
 *   2–4. the land over the bottom half (lib/horizon/Land.kt: made once per
 *      screen and zoom, placed by the camera's distance alone, one number a
 *      frame): the far wall of haze, then the strips from the far end to
 *      the near, so nearer hills cover farther ones with no depth buffer,
 *      the GPU dropping each triangle's hidden side (back faces).
 * Nothing is allocated per frame. QUALITY below full resolution draws into a
 * smaller frame scaled up after (gl/ScaledTarget.kt). ZOOM narrows the view
 * and grows the cells (lib/horizon/Land.kt HorizonView).
 */
package com.thedungeons2077.fluidmotion.scene.horizon

import android.content.Context
import android.opengl.GLES30
import com.thedungeons2077.fluidmotion.gl.EmptyVao
import com.thedungeons2077.fluidmotion.gl.GlProgram
import com.thedungeons2077.fluidmotion.gl.ScaledTarget
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.Quality
import com.thedungeons2077.fluidmotion.lib.horizon.HorizonDraw
import com.thedungeons2077.fluidmotion.lib.horizon.HorizonParams
import com.thedungeons2077.fluidmotion.lib.horizon.HorizonTones
import com.thedungeons2077.fluidmotion.lib.horizon.HorizonView
import com.thedungeons2077.fluidmotion.lib.horizon.Land
import com.thedungeons2077.fluidmotion.lib.horizon.advanceCamera
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import com.thedungeons2077.fluidmotion.scene.Scene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** The HORIZON scene. */
class HorizonRenderer(context: Context) : Scene {
    private val sky = GlProgram(context.assets, "hz_full.vert", "horizon_sky.frag")
    private val land = GlProgram(context.assets, "hz_land.vert", "hz_land.frag")
    private val empty = EmptyVao()
    private val target = ScaledTarget(context.assets)

    private var tones = HorizonTones(Palettes.SYNTH.colors)
    private var glowLevel = GlowLevel.DEFAULT
    private var quality = Quality.DEFAULT
    private var zoom = 1f
    private var screenW = 0
    private var screenH = 0
    private var density = 1f

    // The framing and the land for it (made again when the cell changes), and the camera's distance (units, wrapped).
    private var view: HorizonView? = null
    private var ground: Land? = null
    private var camZ = 0f

    // The land's triangles, sent once per land.
    private var meshVbo = 0
    private var meshVao = 0

    // Set every frame.
    private val camZAt = land.u("uCamZ")

    private var targetDirty = true
    private var dirty = true

    override fun resize(width: Int, height: Int, density: Float) {
        if (width == screenW && height == screenH && density == this.density) return
        screenW = width
        screenH = height
        this.density = density
        targetDirty = true
    }

    /** The px per dp drawn at: the screen's (or fewer at a lower quality), times the zoom. */
    private val pxPerDp: Float get() = density * quality.scale(density) * zoom

    /** The framing for the target now; a new land if its cell changed (a new screen shape or zoom). */
    private fun frame() {
        val v = HorizonView(target.width.toFloat(), target.height.toFloat(), pxPerDp, zoom)
        view = v
        val old = ground
        if (old == null || old.cell != v.cell || old.cols != Land.colsFor(v.cell, v.halfWidth)) {
            val next = Land.build(Rng(System.nanoTime().toInt()), v.cell, v.halfWidth)
            ground = next
            sendMesh(next)
            camZ %= next.length
        }
        dirty = true
    }

    /** The land's triangles to the GPU: per vertex (x, height), then (strip, row in it, depth offset, corner). */
    private fun sendMesh(l: Land) {
        if (meshVbo == 0) {
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            meshVbo = ids[0]
            GLES30.glGenVertexArrays(1, ids, 0)
            meshVao = ids[0]
            GLES30.glBindVertexArray(meshVao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshVbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, Land.VERTEX * 4, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, Land.VERTEX * 4, 2 * 4)
            GLES30.glBindVertexArray(0)
        }
        val data = Land.mesh(l)
        val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(data).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    override fun render(dt: Float) {
        if (screenW == 0) return
        if (targetDirty) {
            targetDirty = false
            target.resize(screenW, screenH, quality.scale(density))
            frame()
        }
        val v = view ?: return
        val l = ground ?: return
        if (dirty) setUniforms(v, l)
        if (dt > 0f) camZ = advanceCamera(camZ, dt, v.speed, l)

        val w = target.width
        val h = target.height
        target.begin()
        // Every pixel is drawn: the clear only spares a tiled GPU loading the last frame.
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        // 1. The sky: the rows above the horizon (GL counts rows from the bottom), one more for the land to meet.
        val skyFrom = max(0, floor(h * (1f - HorizonParams.HORIZON)).toInt() - 1)
        GLES30.glScissor(0, skyFrom, w, h - skyFrom)
        sky.use()
        empty.drawFullScreen()
        // 2–4. The land, the bottom half: the wall, then the strips far end first (no depth buffer), back faces dropped.
        GLES30.glScissor(0, 0, w, ceil(h * (1f - HorizonParams.HORIZON)).toInt() + 1)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        land.use()
        GLES30.glUniform1f(camZAt, camZ)
        GLES30.glBindVertexArray(meshVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, Land.WALL_VERTICES)
        val near = HorizonDraw.nearStrip(camZ, l)
        val per = l.stripVertices
        if (near > 0) GLES30.glDrawArrays(GLES30.GL_TRIANGLES, HorizonDraw.farFirst(near, l), near * per)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, Land.WALL_VERTICES, (l.rows - near) * per)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        target.end()
    }

    /** Uniforms that hold until the screen, palette, glow, quality or zoom changes. */
    private fun setUniforms(v: HorizonView, l: Land) {
        dirty = false
        val p = HorizonParams
        val g = p.GLOW.getValue(glowLevel)
        // Shared by both programs: the target, the haze, the glow and its core.
        for (prog in listOf(sky, land)) {
            prog.use()
            GLES30.glUniform2f(prog.u("uTarget"), target.width.toFloat(), target.height.toFloat())
            GLES30.glUniform2f(prog.u("uSun"), v.sunX, v.sunY)
            GLES30.glUniform1f(prog.u("uGlowR"), v.short * v.sunScale * p.SUN_GLOW * g.sun)
            GLES30.glUniform2f(prog.u("uDome"), v.short * p.DOME_RX, v.short * p.DOME_RY)
            GLES30.glUniform3fv(prog.u("uSkyTop"), 1, tones.skyTop, 0)
            GLES30.glUniform3fv(prog.u("uSkyLow"), 1, tones.skyLow, 0)
            GLES30.glUniform3fv(prog.u("uGlow"), 1, tones.glow, 0)
            GLES30.glUniform1f(prog.u("uCoreR"), v.short * v.sunScale * p.SUN_CORE)
            GLES30.glUniform3fv(prog.u("uCore"), 1, tones.core, 0)
        }
        land.use()
        GLES30.glUniform1f(land.u("uDensity"), v.density)
        GLES30.glUniform1f(land.u("uFocal"), v.focal)
        GLES30.glUniform1f(land.u("uHorizon"), v.horizon)
        GLES30.glUniform1f(land.u("uCamH"), p.CAMERA_HEIGHT)
        GLES30.glUniform1f(land.u("uNear"), p.NEAR)
        GLES30.glUniform1f(land.u("uFar"), p.DEPTH + 3f)
        GLES30.glUniform1f(land.u("uRows"), l.rows.toFloat())
        GLES30.glUniform1f(land.u("uCell"), l.cell)
        GLES30.glUniform3fv(land.u("uLine"), 1, tones.line, 0)
        GLES30.glUniform3fv(land.u("uFace"), 1, tones.face, 0)
        GLES30.glUniform1f(land.u("uLineW"), p.LINE_WIDTH)
        GLES30.glUniform1f(land.u("uGlowW"), p.LINE_GLOW)
        GLES30.glUniform1f(land.u("uGlowA"), g.edge)
        GLES30.glUniform1f(land.u("uSoftNear"), p.LINE_NEAR)
        GLES30.glUniform1f(land.u("uSoften"), p.LINE_SOFTEN)
        GLES30.glUniform1f(land.u("uFogStart"), p.FOG_START)
        GLES30.glUniform1f(land.u("uFogEnd"), p.FOG_END)
    }

    override fun setPalette(colors: PaletteColors) {
        tones = HorizonTones(colors)
        dirty = true
    }

    override fun setGlow(glow: GlowLevel) {
        if (glow == glowLevel) return
        glowLevel = glow
        dirty = true
    }

    override fun setQuality(quality: Quality) {
        if (quality == this.quality) return
        this.quality = quality
        targetDirty = true
    }

    override fun setZoom(zoom: Float) {
        if (zoom == this.zoom) return
        this.zoom = zoom
        targetDirty = true
    }

    override fun release() {
        if (meshVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(meshVbo), 0)
        if (meshVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(meshVao), 0)
        meshVbo = 0
        meshVao = 0
        target.release()
        empty.release()
        sky.release()
        land.release()
        ground = null
        view = null
    }
}
