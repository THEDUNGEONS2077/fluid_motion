/*
 * scene/sunset/SunsetRenderer.kt: SUNSET on the GPU: the outrun sun over a
 * glowing grid that glides toward you, drawn with cyberdeck_terminal's own
 * shaders (ss_*: one set, byte for byte).
 *
 * Layer: scene (GL). One pass, two draw calls, no geometry, no textures:
 *   1. the sky above the horizon (ss_sky.frag: the night sky, stars, the
 *      striped sun and its glow, the scanlines). The web draws it once and
 *      keeps it; a phone's screen doesn't keep its last picture, and
 *      reading a kept copy back every frame costs more battery (memory
 *      traffic) than working it out again per pixel;
 *   2. the ground below it (ss_ground.frag: the grid per pixel, melting into
 *      the sky at the horizon). Its only input that changes is how far the
 *      grid has come: one number a frame.
 * Nothing is allocated per frame. QUALITY below full resolution draws into a
 * smaller frame scaled up after (gl/ScaledTarget.kt). The phone's framing
 * (the sun scaled by the short side, the camera risen on a narrow screen)
 * is lib/sunset/Sunset.kt SunsetView. No glow setting: the sun's glow is
 * the reference's own, as on the web.
 */
package com.thedungeons2077.fluidmotion.scene.sunset

import android.content.Context
import android.opengl.GLES30
import com.thedungeons2077.fluidmotion.gl.EmptyVao
import com.thedungeons2077.fluidmotion.gl.GlProgram
import com.thedungeons2077.fluidmotion.gl.ScaledTarget
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.Quality
import com.thedungeons2077.fluidmotion.lib.sunset.SunsetParams
import com.thedungeons2077.fluidmotion.lib.sunset.SunsetTones
import com.thedungeons2077.fluidmotion.lib.sunset.SunsetView
import com.thedungeons2077.fluidmotion.lib.sunset.advanceTravel
import com.thedungeons2077.fluidmotion.scene.Scene
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** The SUNSET scene. */
class SunsetRenderer(context: Context) : Scene {
    private val sky = GlProgram(context.assets, "ss_full.vert", "ss_sky.frag")
    private val ground = GlProgram(context.assets, "ss_full.vert", "ss_ground.frag")
    private val empty = EmptyVao()
    private val target = ScaledTarget(context.assets)

    private var tones = SunsetTones(Palettes.OUTRUN.colors)
    private var quality = Quality.DEFAULT
    private var zoom = 1f
    private var screenW = 0
    private var screenH = 0
    private var density = 1f

    // The framing, and how far the grid has come (units, wrapped at WRAP).
    private var view: SunsetView? = null
    private var travel = 0f

    // Set every frame.
    private val travelAt = ground.u("uTravel")

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

    override fun render(dt: Float) {
        if (screenW == 0) return
        if (targetDirty) {
            targetDirty = false
            target.resize(screenW, screenH, quality.scale(density))
            view = SunsetView(target.width.toFloat(), target.height.toFloat(), pxPerDp, zoom)
            dirty = true
        }
        val v = view ?: return
        if (dirty) setUniforms(v)
        if (dt > 0f) travel = advanceTravel(travel, dt, v.speed)

        val w = target.width
        val h = target.height
        target.begin()
        // Every pixel is drawn: the clear only spares a tiled GPU loading the last frame.
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        // 1. The sky: the rows above the horizon (GL counts rows from the bottom), one more for the ground to meet.
        val groundRows = ceil(h * (1f - SunsetParams.HORIZON)).toInt()
        val skyFrom = max(0, floor(h * (1f - SunsetParams.HORIZON)).toInt() - 1)
        GLES30.glScissor(0, skyFrom, w, h - skyFrom)
        sky.use()
        empty.drawFullScreen()
        // 2. The ground: the rows below the horizon, as the web's scissor.
        GLES30.glScissor(0, 0, w, groundRows)
        ground.use()
        GLES30.glUniform1f(travelAt, travel)
        empty.drawFullScreen()
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        target.end()
    }

    /** Uniforms that hold until the screen, palette, quality or zoom changes. */
    private fun setUniforms(v: SunsetView) {
        dirty = false
        val p = SunsetParams
        for (prog in listOf(sky, ground)) {
            prog.use()
            GLES30.glUniform2f(prog.u("uTarget"), target.width.toFloat(), target.height.toFloat())
            GLES30.glUniform1f(prog.u("uUnit"), v.pxPerDp)
            GLES30.glUniform1f(prog.u("uHorizon"), v.horizon)
            GLES30.glUniform1f(prog.u("uScanEvery"), p.SCAN_EVERY)
            GLES30.glUniform1f(prog.u("uScanDepth"), p.SCAN_DEPTH)
            // The sky's colours, the sun and its glow: the ground needs them too, to melt into the sky at the horizon.
            GLES30.glUniform2f(prog.u("uSun"), v.sunX, v.sunY)
            GLES30.glUniform1f(prog.u("uSunR"), p.SUN_R * v.big)
            GLES30.glUniform1f(prog.u("uGlowR"), p.SUN_GLOW * v.big)
            GLES30.glUniform3fv(prog.u("uSkyLeft"), 1, tones.skyLeft, 0)
            GLES30.glUniform3fv(prog.u("uSkyRight"), 1, tones.skyRight, 0)
            GLES30.glUniform3fv(prog.u("uGlow"), 1, tones.glow, 0)
        }
        sky.use()
        GLES30.glUniform3fv(sky.u("uSkyTop"), 1, tones.skyTop, 0)
        GLES30.glUniform3fv(sky.u("uSunTop"), 1, tones.sunTop, 0)
        GLES30.glUniform3fv(sky.u("uSunBase"), 1, tones.sunBase, 0)
        GLES30.glUniform3fv(sky.u("uStar"), 1, tones.star, 0)
        GLES30.glUniform3f(sky.u("uStripe"), p.STRIPE_FROM, p.STRIPE_PERIOD, p.GAP_FIRST)
        GLES30.glUniform1f(sky.u("uGapGrow"), p.GAP_LAST - p.GAP_FIRST)
        GLES30.glUniform1f(sky.u("uStarCell"), p.STAR_CELL)
        GLES30.glUniform1f(sky.u("uStarChance"), p.STAR_CHANCE)
        ground.use()
        GLES30.glUniform1f(ground.u("uFocal"), v.focal)
        GLES30.glUniform1f(ground.u("uCamH"), v.cameraHeight)
        GLES30.glUniform1f(ground.u("uLine"), p.LINE)
        GLES30.glUniform1f(ground.u("uGlowW"), p.LINE_GLOW)
        GLES30.glUniform3f(ground.u("uWave"), p.WAVE_AMOUNT, p.WAVE_K * p.WAVE_ACROSS, p.WAVE_K * p.WAVE_ALONG)
        GLES30.glUniform1f(ground.u("uDrift"), p.WAVE_K * 5f)
        GLES30.glUniform1f(ground.u("uMeltH"), v.melt)
        GLES30.glUniform3fv(ground.u("uGround"), 1, tones.ground, 0)
        GLES30.glUniform3fv(ground.u("uWarm"), 1, tones.warm, 0)
        GLES30.glUniform3fv(ground.u("uCool"), 1, tones.coolLine, 0)
    }

    override fun setPalette(colors: PaletteColors) {
        tones = SunsetTones(colors)
        dirty = true
    }

    /** No glow setting: the sun's glow is the reference's own (the web's SUNSET ignores it too). */
    override fun setGlow(glow: GlowLevel) {}

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
        target.release()
        empty.release()
        sky.release()
        ground.release()
        view = null
    }
}
