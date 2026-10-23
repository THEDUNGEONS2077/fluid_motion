/*
 * scene/rain/RainRenderer.kt: MATRIX RAIN on the GPU: the simulation
 * (lib/rain/RainSim.kt) says what changed, this draws just that.
 *
 * Layer: scene (GL). As on the web, the rain lives on a layer of its own
 * that keeps its pixels between frames (here a texture, the "trail
 * layer"), and each frame only:
 *   1. fades two thin stripe sets of it (that fading *is* the trails),
 *   2. cuts out and draws the glyphs drops moved onto (a few hundred),
 * all as one instanced draw call, in the simulation's order, with one blend
 * (rain_ops.frag says why one is enough). Then one pass puts the layer on
 * the screen over the background and haze, under the floor
 * (rain_composite.frag). Per frame: no allocation, two draw calls, a few
 * kilobytes sent to the GPU.
 *
 * The trail layer is drawn at the screen's own resolution up to
 * MAX_PX_PER_DP (beyond that the glyphs are as sharp as the eye can tell,
 * and every pixel is memory traffic). A new palette applies to glyphs drawn
 * from then on, so the change rains in, as on the web; a new glow level
 * draws a new atlas, the same way.
 */
package com.thedungeons2077.fluidmotion.scene.rain

import android.content.Context
import android.graphics.Typeface
import android.opengl.GLES30
import com.thedungeons2077.fluidmotion.R
import com.thedungeons2077.fluidmotion.gl.EmptyVao
import com.thedungeons2077.fluidmotion.gl.GlProgram
import com.thedungeons2077.fluidmotion.gl.InstanceStream
import com.thedungeons2077.fluidmotion.gl.uniformRgb
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.desaturate
import com.thedungeons2077.fluidmotion.lib.rain.RainParams
import com.thedungeons2077.fluidmotion.lib.rain.RainRecorder
import com.thedungeons2077.fluidmotion.lib.rain.RainSim
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import com.thedungeons2077.fluidmotion.scene.Scene
import kotlin.math.min
import kotlin.math.roundToInt

/** The trail layer's sharpest: px per dp (the S25 Ultra at its default FHD+ is 2.8: drawn at full resolution). */
private const val MAX_PX_PER_DP = 3f

/** Instance records per draw (the pre-warm's paint takes several; a frame, one). */
private const val BATCH = 4096

/** The matrix rain scene. */
class RainRenderer(private val context: Context) : Scene {
    private val ops = GlProgram(context.assets, "rain_ops.vert", "rain_ops.frag")
    private val composite = GlProgram(context.assets, "fullscreen.vert", "rain_composite.frag")
    private val stream = InstanceStream(stride = 4, capacity = BATCH).apply {
        attribute(0, 2, 0)
        attribute(1, 1, 2)
        attribute(2, 1, 3)
    }
    private val empty = EmptyVao()
    private val typeface: Typeface = context.resources.getFont(R.font.jetbrains_mono_regular)

    private var colors: PaletteColors = Palettes.PHOSPHOR.colors
    private var glow = GlowLevel.DEFAULT
    private var atlas: GlyphAtlas? = null
    private var sim: RainSim? = null

    // The screen (px), and the trail layer: its texture, framebuffer, size and px per dp.
    private var screenW = 0
    private var screenH = 0
    private var density = 0f
    private var layerTex = 0
    private var layerFbo = 0
    private var layerW = 0
    private var layerH = 0
    private var pxPerDp = 1f

    // The ops program's uniforms are set only when something they hold changes (a GL program keeps them).
    private var opsDirty = true
    private var compositeDirty = true
    private val pitch = FloatArray(6)
    private val cell = FloatArray(6)
    private val origin = FloatArray(3)

    /** The simulation's drawing, as instance records in its order (lib/rain/RainRecords.kt); a full batch is drawn first (order kept). */
    private val recorder = RainRecorder { x, y, code, alpha ->
        if (stream.full) stream.drawQuads()
        stream.next().put(x).put(y).put(code).put(alpha)
    }

    override fun resize(width: Int, height: Int, density: Float) {
        if (width == screenW && height == screenH && density == this.density && sim != null) return
        screenW = width
        screenH = height
        this.density = density
        val scale = min(1f, MAX_PX_PER_DP / density)
        val nextPx = density * scale
        // A new atlas only if the glyphs' size changed.
        if (atlas == null || nextPx != pxPerDp) {
            atlas?.release()
            pxPerDp = nextPx
            atlas = GlyphAtlas.build(typeface, pxPerDp, RainParams.GLOW.getValue(glow))
        }
        makeLayer((width * scale).roundToInt(), (height * scale).roundToInt())
        recorder.layerH = layerH
        opsDirty = true
        // A new rain for the screen, the screen opening full (the pre-warm paints it).
        val a = atlas!!
        recorder.slotOf = a.slotOf
        sim = RainSim(width / density, height / density, a.glyphCount, Rng(System.nanoTime().toInt())).also { s ->
            beginLayer()
            s.prewarm(recorder)
            stream.drawQuads()
        }
    }

    /** A new, clear trail layer of [w] × [h] px. */
    private fun makeLayer(w: Int, h: Int) {
        releaseLayer()
        layerW = w
        layerH = h
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        layerTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, layerTex)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h)
        // Linear: when the layer is drawn smaller than the screen (very dense screens), it's scaled up smoothly.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glGenFramebuffers(1, ids, 0)
        layerFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, layerFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, layerTex, 0)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Binds the trail layer for drawing with the ops program (records then flush into it). */
    private fun beginLayer() {
        val a = atlas ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, layerFbo)
        GLES30.glViewport(0, 0, layerW, layerH)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        ops.use()
        if (opsDirty) setOpsUniforms(a)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, a.texture)
    }

    /** The ops program's uniforms: the layer's and atlas's geometry, the palette's colours, the glow. */
    private fun setOpsUniforms(a: GlyphAtlas) {
        opsDirty = false
        GLES30.glUniform2f(ops.u("uTarget"), layerW.toFloat(), layerH.toFloat())
        val layers = RainParams.LAYERS
        for (i in 0 until 3) {
            pitch[2 * i] = layers[i].colW * pxPerDp
            pitch[2 * i + 1] = layers[i].rowH * pxPerDp
            cell[2 * i] = a.cellW[i].toFloat()
            cell[2 * i + 1] = a.cellH[i].toFloat()
            origin[i] = a.originY[i].toFloat()
        }
        GLES30.glUniform2fv(ops.u("uPitch"), 3, pitch, 0)
        GLES30.glUniform2fv(ops.u("uCell"), 3, cell, 0)
        GLES30.glUniform1fv(ops.u("uOrigin"), 3, origin, 0)
        GLES30.glUniform4f(ops.u("uPads"), a.padHead.toFloat(), a.padBody.toFloat(), a.padErase.toFloat(), a.padSlot.toFloat())
        GLES30.glUniform2f(ops.u("uAtlas"), a.width.toFloat(), a.height.toFloat())
        GLES30.glUniform1i(ops.u("uPerRow"), a.perRow)
        GLES30.glUniform1f(ops.u("uStripe"), RainParams.FADE_STRIPE.toFloat())
        val g = RainParams.GLOW.getValue(glow)
        uniformRgb(ops.u("uBright"), colors.textBright)
        uniformRgb(ops.u("uBody"), desaturate(colors.text, RainParams.BODY_DESATURATE))
        uniformRgb(ops.u("uDim"), desaturate(colors.textDim, RainParams.BODY_DESATURATE))
        uniformRgb(ops.u("uGlowColor"), colors.accent)
        GLES30.glUniform1f(ops.u("uGlowAlpha"), g.alpha)
        GLES30.glUniform1f(ops.u("uHeadLayers"), g.headLayers.toFloat())
        GLES30.glUniform1i(ops.u("uGlyphs"), 0)
    }

    override fun render(dt: Float) {
        val s = sim ?: return
        // 1. The trail layer: this frame's fades, erases and glyphs.
        beginLayer()
        s.step(min(dt, RainParams.MAX_FRAME_STEP), recorder)
        stream.drawQuads()
        // 2. The screen: the layer over the background and haze, under the floor.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenW, screenH)
        GLES30.glDisable(GLES30.GL_BLEND)
        // Every pixel is drawn below; the clear tells a tiled GPU not to load the last frame first.
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        composite.use()
        if (compositeDirty) {
            compositeDirty = false
            uniformRgb(composite.u("uBg"), colors.bg)
            uniformRgb(composite.u("uAccent"), colors.accent)
            GLES30.glUniform1i(composite.u("uRain"), 0)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, layerTex)
        empty.drawFullScreen()
    }

    override fun setPalette(colors: PaletteColors) {
        this.colors = colors
        opsDirty = true
        compositeDirty = true
    }

    override fun setGlow(glow: GlowLevel) {
        if (glow == this.glow) return
        this.glow = glow
        // New sprites; glyphs already down fade out as usual, so the change rains in.
        if (atlas != null) {
            atlas?.release()
            atlas = GlyphAtlas.build(typeface, pxPerDp, RainParams.GLOW.getValue(glow))
            recorder.slotOf = atlas!!.slotOf
        }
        opsDirty = true
    }

    override fun release() {
        releaseLayer()
        atlas?.release()
        atlas = null
        sim = null
        stream.release()
        empty.release()
        ops.release()
        composite.release()
    }

    private fun releaseLayer() {
        if (layerFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(layerFbo), 0)
        if (layerTex != 0) GLES30.glDeleteTextures(1, intArrayOf(layerTex), 0)
        layerFbo = 0
        layerTex = 0
    }
}
