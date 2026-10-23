/*
 * scene/velocity/VelocityRenderer.kt: VELOCITY on the GPU: the light from
 * lib/velocity/VelocitySim.kt, drawn as the web draws it.
 *
 * Layer: scene (GL). One pass straight to the screen, four draw calls:
 *   1. the panels (each its own slanted quad), the long lines and streaks,
 *      and the flare, all added up ('lighter', as the web's canvas) on a
 *      clear target, so the target's alpha ends up as the light's;
 *   2. the dark background slid in under it by that alpha (velocity_bg.frag).
 * Per frame the CPU writes a few small uniform arrays; nothing is sent as
 * buffers, nothing allocated.
 *
 * QUALITY: below full resolution it draws into a smaller frame (with its
 * own alpha, for step 2) scaled up after (gl/ScaledTarget.kt).
 */
package com.thedungeons2077.fluidmotion.scene.velocity

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
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import com.thedungeons2077.fluidmotion.lib.velocity.VelocityFrame
import com.thedungeons2077.fluidmotion.lib.velocity.VelocityParams
import com.thedungeons2077.fluidmotion.lib.velocity.VelocitySim
import com.thedungeons2077.fluidmotion.lib.velocity.velocityTones
import com.thedungeons2077.fluidmotion.scene.Scene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** The striation tile's height (dp rows). */
private const val TILE = VelocityFrame.TILE

/** The VELOCITY scene. */
class VelocityRenderer(context: Context) : Scene {
    private val panel = GlProgram(context.assets, "velocity_panel.vert", "velocity_panel.frag")
    private val light = GlProgram(context.assets, "velocity_light.vert", "velocity_light.frag")
    private val flare = GlProgram(context.assets, "velocity_flare.vert", "velocity_flare.frag")
    private val bg = GlProgram(context.assets, "fullscreen.vert", "velocity_bg.frag")
    private val empty = EmptyVao()
    private val target = ScaledTarget(context.assets)
    private var quality = Quality.DEFAULT
    private var targetDirty = true

    private var colors: PaletteColors = Palettes.BLOOD.colors
    private var glow = GlowLevel.DEFAULT
    private var sim: VelocitySim? = null
    private var screenW = 0
    private var screenH = 0
    private var density = 1f
    private var zoom = 1f
    // A new zoom: the scene is made again at the next frame (with the context current).
    private var layoutDirty = false
    private var tile = 0
    private var dirty = true

    // Per-frame uniform arrays, made once.
    private val panels = FloatArray(4 * 4)
    private val lines = FloatArray(VelocityParams.LONG_LINES * 4)
    private val streaks = FloatArray(VelocityParams.MAX_STREAKS * 4)
    private val looks = FloatArray(VelocityParams.MAX_STREAKS * 4)
    private val box = FloatArray(4)

    override fun resize(width: Int, height: Int, density: Float) {
        if (width == screenW && height == screenH && density == this.density && sim != null) return
        screenW = width
        screenH = height
        this.density = density
        build()
    }

    /** A new scene at the zoom: laid out for a screen 1/zoom as big (dp), its sizes grown by the zoom (VelocitySim). */
    private fun build() {
        layoutDirty = false
        val dp = density * zoom
        val rng = Rng(System.nanoTime().toInt())
        sim = VelocitySim(screenW / dp, screenH / dp, rng, zoom)
        makeTile(rng)
        dirty = true
        targetDirty = true
    }

    /** The px per dp drawn at: the screen's (or fewer at a lower quality), times the zoom. */
    private val pxPerDp: Float get() = density * quality.scale(density) * zoom

    /** The fine motion-blur lines: one brightness per dp row, mostly faint, now and then bright (the web's pattern). */
    private fun makeTile(rng: Rng) {
        if (tile != 0) GLES30.glDeleteTextures(1, intArrayOf(tile), 0)
        val rows = ByteBuffer.allocateDirect(TILE).order(ByteOrder.nativeOrder())
        rows.put(VelocityFrame.striationTile(rng)).position(0)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        tile = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tile)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 1, TILE, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, rows)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
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
        val g = VelocityParams.GLOW.getValue(glow)

        target.begin()
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        // 1. Light, added up.
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)

        // The panels, breathing slowly.
        val np = VelocityFrame.panels(s, g, panels)
        panel.use()
        GLES30.glUniform4fv(panel.u("uPanels"), np, panels, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tile)
        empty.drawQuads(np)

        // The long lines, flickering slowly, then the streaks.
        val nl = VelocityFrame.lines(s, g, lines)
        val ns = VelocityFrame.streaks(s, streaks, looks)
        light.use()
        GLES30.glUniform4fv(light.u("uLines"), nl, lines, 0)
        if (ns > 0) {
            GLES30.glUniform4fv(light.u("uStreaks"), ns, streaks, 0)
            GLES30.glUniform4fv(light.u("uStreakLook"), ns, looks, 0)
        }
        empty.drawQuads(nl + ns)

        // The corner flare, breathing.
        flare.use()
        GLES30.glUniform1f(flare.u("uStrength"), VelocityFrame.flareStrength(s, g))
        empty.drawQuads(2)

        // 2. The background, under the light.
        GLES30.glBlendFunc(GLES30.GL_ONE_MINUS_DST_ALPHA, GLES30.GL_ONE)
        bg.use()
        empty.drawFullScreen()
        GLES30.glDisable(GLES30.GL_BLEND)
        target.end()
    }

    /** Uniforms that hold until the screen, palette or glow changes. */
    private fun setUniforms(s: VelocitySim) {
        dirty = false
        val tones = velocityTones(colors)
        val g = VelocityParams.GLOW.getValue(glow)
        panel.use()
        GLES30.glUniform2f(panel.u("uTargetPx"), target.width.toFloat(), target.height.toFloat())
        GLES30.glUniform1f(panel.u("uDensity"), pxPerDp)
        GLES30.glUniform2f(panel.u("uScreen"), s.w, s.h)
        GLES30.glUniform1f(panel.u("uScreenH"), s.h)
        uniformRgb(panel.u("uPale"), tones.pale)
        uniformRgb(panel.u("uRed"), tones.red)
        GLES30.glUniform1f(panel.u("uSoft"), VelocityParams.SOFT_EDGE)
        GLES30.glUniform1f(panel.u("uStriation"), VelocityParams.STRIATION)
        GLES30.glUniform1i(panel.u("uTile"), 0)
        light.use()
        GLES30.glUniform2f(light.u("uTargetPx"), target.width.toFloat(), target.height.toFloat())
        GLES30.glUniform1f(light.u("uDensity"), pxPerDp)
        GLES30.glUniform2f(light.u("uScreen"), s.w, s.h)
        GLES30.glUniform1i(light.u("uLineCount"), VelocityParams.LONG_LINES)
        uniformRgb(light.u("uPale"), tones.pale)
        uniformRgb(light.u("uRed"), tones.red)
        GLES30.glUniform1f(light.u("uGlow"), g)
        flare.use()
        GLES30.glUniform2f(flare.u("uTargetPx"), target.width.toFloat(), target.height.toFloat())
        GLES30.glUniform1f(flare.u("uDensity"), pxPerDp)
        GLES30.glUniform3f(flare.u("uBloom"), s.flareX, s.flareY, s.flareR)
        GLES30.glUniform3f(flare.u("uArc"), s.arcX, s.arcY, s.arcR)
        GLES30.glUniform2f(flare.u("uArcSpan"), VelocityParams.ARC_FROM, VelocityParams.ARC_TO)
        GLES30.glUniform1f(flare.u("uArcHalf"), VelocityParams.ARC_WIDTH / 2f)
        GLES30.glUniform1f(flare.u("uArcAlpha"), VelocityParams.ARC_ALPHA)
        uniformRgb(flare.u("uPale"), tones.pale)
        uniformRgb(flare.u("uFlare"), tones.flare)
        VelocityFrame.arcBox(s, pxPerDp, box)
        GLES30.glUniform4f(flare.u("uArcBox"), box[0], box[1], box[2], box[3])
        bg.use()
        uniformRgb(bg.u("uBg"), colors.bg)
        uniformRgb(bg.u("uCool"), colors.cool)
        uniformRgb(bg.u("uAccent"), colors.accent)
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
        if (tile != 0) GLES30.glDeleteTextures(1, intArrayOf(tile), 0)
        tile = 0
        target.release()
        empty.release()
        panel.release()
        light.release()
        flare.release()
        bg.release()
        sim = null
    }

    private companion object {
        /** The longest step the motion takes in one frame (s). */
        const val MAX_FRAME_STEP = 0.1f
    }
}
