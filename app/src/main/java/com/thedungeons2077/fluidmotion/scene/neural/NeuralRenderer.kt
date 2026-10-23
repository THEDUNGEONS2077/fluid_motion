/*
 * scene/neural/NeuralRenderer.kt: NEURAL NET on the GPU: the plexus from
 * lib/neural/NeuralSim.kt, drawn as the web draws it (bokeh, then links in
 * a soft wide pass and a thin one, then nodes and signals), over its
 * background light.
 *
 * Layer: scene (GL). One pass straight to the screen, five draw calls. Per
 * frame the CPU only writes positions into three small uniform arrays
 * (nodes, sparks, bokeh); the links, which never change on a screen, are
 * sent once. Colours along the diagonal are worked out in the vertex
 * shaders (neural_shade.glsl), so a palette change is four uniforms.
 *
 * QUALITY: below full resolution it draws into a smaller frame scaled up
 * after (gl/ScaledTarget.kt); LOW also leaves out the links' soft glow
 * pass, its widest.
 */
package com.thedungeons2077.fluidmotion.scene.neural

import android.content.Context
import android.opengl.GLES30
import com.thedungeons2077.fluidmotion.gl.EmptyVao
import com.thedungeons2077.fluidmotion.gl.GlProgram
import com.thedungeons2077.fluidmotion.gl.ScaledTarget
import com.thedungeons2077.fluidmotion.gl.uniformRgb
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.Quality
import com.thedungeons2077.fluidmotion.lib.mixRgb
import com.thedungeons2077.fluidmotion.lib.neural.NeuralFrame
import com.thedungeons2077.fluidmotion.lib.neural.NeuralParams
import com.thedungeons2077.fluidmotion.lib.neural.NeuralSim
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import com.thedungeons2077.fluidmotion.scene.Scene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** The neural net scene. */
class NeuralRenderer(context: Context) : Scene {
    private val bg = GlProgram(context.assets, "fullscreen.vert", "neural_bg.frag")
    private val bokeh = GlProgram(context.assets, "neural_bokeh.vert", "neural_bokeh.frag")
    private val link = GlProgram(context.assets, "neural_link.vert", "neural_link.frag")
    private val spark = GlProgram(context.assets, "neural_spark.vert", "neural_spark.frag")
    private val empty = EmptyVao()
    private val target = ScaledTarget(context.assets)

    private var colors: PaletteColors = Palettes.BLOOD.colors
    private var quality = Quality.DEFAULT
    private var targetDirty = true
    private var glow = GlowLevel.DEFAULT
    private var sim: NeuralSim? = null
    private var screenW = 0
    private var screenH = 0
    private var density = 1f
    private var zoom = 1f
    // A new zoom: the net is made again at the next frame (with the context current).
    private var layoutDirty = false

    // The links on this screen (node a, node b, weight), sent once.
    private var linkVbo = 0
    private var linkVao = 0
    private var linkCount = 0

    // Per-frame uniform arrays, made once.
    private val nodes = FloatArray(NeuralFrame.MAX_NODES * 2)
    private val sparks = FloatArray(NeuralFrame.MAX_SPARKS * 4)
    private val bokehs = FloatArray(NeuralFrame.MAX_BOKEH * 4)

    // Uniforms that change only with the screen, palette or glow.
    private var dirty = true

    override fun resize(width: Int, height: Int, density: Float) {
        if (width == screenW && height == screenH && density == this.density && sim != null) return
        screenW = width
        screenH = height
        this.density = density
        build()
    }

    /**
     * A new net for the screen at the zoom: laid out for a screen 1/zoom as
     * big (dp), with its nodes and bokeh for the area that shows (NeuralSim).
     */
    private fun build() {
        layoutDirty = false
        val dp = density * zoom
        val s = NeuralSim(screenW / dp, screenH / dp, Rng(System.nanoTime().toInt()), zoom)
        sim = s
        check(s.n <= NeuralFrame.MAX_NODES && s.nb <= NeuralFrame.MAX_BOKEH) { "scene larger than the shaders' arrays" }
        sendLinks(s)
        dirty = true
        targetDirty = true
    }

    /** The px per dp drawn at: the screen's (or fewer at a lower quality), times the zoom. */
    private val pxPerDp: Float get() = density * quality.scale(density) * zoom

    /** The links as a static instance buffer: two node numbers and a weight each. */
    private fun sendLinks(s: NeuralSim) {
        releaseLinks()
        linkCount = s.links.size / 2
        val data = ByteBuffer.allocateDirect(linkCount * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (k in 0 until linkCount) data.put(s.links[2 * k].toFloat()).put(s.links[2 * k + 1].toFloat()).put(s.weight[k])
        data.position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        linkVbo = ids[0]
        GLES30.glGenVertexArrays(1, ids, 0)
        linkVao = ids[0]
        GLES30.glBindVertexArray(linkVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, linkVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, linkCount * 3 * 4, data, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glVertexAttribDivisor(0, 1)
        GLES30.glBindVertexArray(0)
    }

    override fun render(dt: Float) {
        if (layoutDirty && screenW > 0) build()
        val s = sim ?: return
        if (dt > 0f) s.advance(min(dt, MAX_FRAME_STEP))
        if (targetDirty) {
            targetDirty = false
            target.resize(screenW, screenH, quality.scale(density))
            dirty = true
        }
        if (dirty) setUniforms(s)
        val g = NeuralParams.GLOW.getValue(glow)

        target.begin()
        // 1. The background light (every pixel: the clear only spares a tiled GPU loading the last frame).
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_BLEND)
        bg.use()
        empty.drawFullScreen()
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // 2. Bokeh, behind everything.
        val nb = NeuralFrame.bokeh(s, bokehs)
        bokeh.use()
        GLES30.glUniform4fv(bokeh.u("uBokeh"), nb, bokehs, 0)
        empty.drawQuads(nb)

        // 3. Links: a soft wide pass (the glow), then the thin lines.
        val pairs = NeuralFrame.nodes(s, nodes)
        link.use()
        GLES30.glUniform4fv(link.u("uNodes"), pairs, nodes, 0)
        GLES30.glBindVertexArray(linkVao)
        if (g.lineGlow > 0f && quality.linkGlow) {
            GLES30.glUniform1f(link.u("uWidth"), NeuralParams.LINE_WIDTH * NeuralFrame.GLOW_WIDTH)
            GLES30.glUniform1f(link.u("uStrength"), g.lineGlow)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, linkCount)
        }
        GLES30.glUniform1f(link.u("uWidth"), NeuralParams.LINE_WIDTH)
        GLES30.glUniform1f(link.u("uStrength"), 1f)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, linkCount)
        GLES30.glBindVertexArray(0)

        // 4. Nodes (bigger and brighter while lit), then the signals.
        val k = NeuralFrame.sparks(s, g.haloSize, pxPerDp, sparks)
        spark.use()
        GLES30.glUniform4fv(spark.u("uSparks"), k, sparks, 0)
        empty.drawQuads(k)
        target.end()
    }

    /** Uniforms that hold until the screen, palette or glow changes. */
    private fun setUniforms(s: NeuralSim) {
        dirty = false
        val g = NeuralParams.GLOW.getValue(glow)
        val cool = mixRgb(colors.cool, 0xffffff, 0.4f)
        bg.use()
        uniformRgb(bg.u("uBg"), colors.bg)
        uniformRgb(bg.u("uCool"), colors.cool)
        uniformRgb(bg.u("uAccent"), colors.accent)
        for (p in listOf(bokeh, link, spark)) {
            p.use()
            GLES30.glUniform2f(p.u("uTargetPx"), target.width.toFloat(), target.height.toFloat())
            GLES30.glUniform1f(p.u("uDensity"), pxPerDp)
            GLES30.glUniform2f(p.u("uScreen"), s.w, s.h)
            uniformRgb(p.u("uCool"), cool)
            uniformRgb(p.u("uBright"), colors.textBright)
            uniformRgb(p.u("uAccent"), colors.accent)
        }
        link.use()
        GLES30.glUniform1f(link.u("uMaxLink"), s.maxLink)
        spark.use()
        GLES30.glUniform1f(spark.u("uEdge"), 1f / NeuralFrame.spriteScale(g.haloSize, pxPerDp))
        GLES30.glUniform1f(spark.u("uHalo"), g.halo)
    }

    override fun setPalette(colors: PaletteColors) {
        this.colors = colors
        dirty = true
    }

    override fun setGlow(glow: GlowLevel) {
        this.glow = glow
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
        if (sim != null) layoutDirty = true
    }

    override fun release() {
        releaseLinks()
        target.release()
        empty.release()
        bg.release()
        bokeh.release()
        link.release()
        spark.release()
        sim = null
    }

    private fun releaseLinks() {
        if (linkVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(linkVbo), 0)
        if (linkVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(linkVao), 0)
        linkVbo = 0
        linkVao = 0
    }

    private companion object {
        /** The longest step the motion takes in one frame (s): after a stall it carries on, it doesn't leap. */
        const val MAX_FRAME_STEP = 0.1f
    }
}
