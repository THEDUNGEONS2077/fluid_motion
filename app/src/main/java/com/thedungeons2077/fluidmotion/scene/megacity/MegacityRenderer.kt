/*
 * scene/megacity/MegacityRenderer.kt: MEGACITY on the GPU: the city from
 * lib/megacity/City.kt, its motion from lib/megacity/CitySim.kt, drawn
 * with cyberdeck_terminal's own shaders (mc_*.glsl: one set, byte for byte).
 *
 * Layer: scene (GL). One pass, four draw calls:
 *   1. the city: its layers of towers come from a small float texture of
 *      skylines sent once per city; windows, edges and haze are worked out
 *      per pixel (mc_city.frag), the windows going on and off by the clock.
 *      That's most of the frame's cost (5 ms a frame at ULTRA on the S25
 *      Ultra, 2026-10-24), yet the city only changes when a window does: its
 *      clock is used for nothing else. So the city is drawn into a cache
 *      once a second (its clock in whole seconds: a window switches on the
 *      second's edge, not a fraction after, which no one can see), and each
 *      frame copies the cache: one texture read per pixel. A new city,
 *      size, palette, glow or quality draws it again at once;
 *   2. the signs, 3. the haze patches, beacons and drones: a few kilobytes
 *      a frame, from arrays made once;
 *   4. the railing and the ledge: triangles sent once per city,
 *      palette, quality and zoom.
 * Nothing is allocated per frame. QUALITY below full resolution draws into a
 * smaller frame scaled up after (gl/ScaledTarget.kt); LOW also leaves out
 * the windows' halo. ZOOM lays the city out for a screen 1/zoom as big; the
 * ledge keeps its place.
 */
package com.thedungeons2077.fluidmotion.scene.megacity

import android.content.Context
import android.opengl.GLES30
import com.thedungeons2077.fluidmotion.gl.EmptyVao
import com.thedungeons2077.fluidmotion.gl.GlProgram
import com.thedungeons2077.fluidmotion.gl.InstanceStream
import com.thedungeons2077.fluidmotion.gl.ScaledTarget
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.Quality
import com.thedungeons2077.fluidmotion.lib.megacity.City
import com.thedungeons2077.fluidmotion.lib.megacity.CityFrame
import com.thedungeons2077.fluidmotion.lib.megacity.CityLayout
import com.thedungeons2077.fluidmotion.lib.megacity.CitySim
import com.thedungeons2077.fluidmotion.lib.megacity.CityTones
import com.thedungeons2077.fluidmotion.lib.megacity.MegacityParams
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import com.thedungeons2077.fluidmotion.scene.Scene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.min

/** The MEGACITY scene. */
class MegacityRenderer(context: Context) : Scene {
    private val city = GlProgram(context.assets, "mc_full.vert", "mc_city.frag")
    private val sign = GlProgram(context.assets, "mc_sign.vert", "mc_sign.frag")
    private val glow = GlProgram(context.assets, "mc_glow.vert", "mc_glow.frag")
    private val shape = GlProgram(context.assets, "mc_shape.vert", "mc_shape.frag")
    private val empty = EmptyVao()
    private val target = ScaledTarget(context.assets)
    // The city's cache: drawn once a second, copied each frame (the copy is the QUALITY upscale's shader, at one to one).
    private val copy = GlProgram(context.assets, "fullscreen.vert", "upscale.frag")
    private var cacheTexture = 0
    private var cacheFbo = 0
    /** The whole second the cache was drawn at; NaN: it must be drawn. */
    private var cachedSecond = Float.NaN

    private var colors: PaletteColors = Palettes.NEON.colors
    private var tones: CityTones = CityLayout.tones(colors)
    private var glowLevel = GlowLevel.DEFAULT
    private var quality = Quality.DEFAULT
    private var zoom = 1f
    private var layout: City? = null
    private var sim: CitySim? = null
    private var screenW = 0
    private var screenH = 0
    private var density = 1f

    // The skyline texture, and the foreground's triangles (sent once per city, palette, quality and zoom).
    private var skyline = 0
    private var shapeVbo = 0
    private var shapeVao = 0
    private var shapeCount = 0

    // This frame's lights: arrays filled each frame (lib/megacity/CitySim.kt CityFrame), streamed to the GPU.
    private var signArray = FloatArray(0)
    private var glowArray = FloatArray(0)
    private var signs: InstanceStream? = null
    private var glows: InstanceStream? = null
    private val scratch = FloatArray(3)

    // Set every frame.
    private val timeAt = city.u("uTime")

    private var layoutDirty = false
    private var targetDirty = true
    private var foregroundDirty = true
    private var dirty = true

    /** A stream of instances of [stride] floats, its attributes [sizes] floats each, in order. */
    private fun stream(stride: Int, capacity: Int, sizes: IntArray) = InstanceStream(stride, capacity).apply {
        var offset = 0
        sizes.forEachIndexed { loc, size ->
            attribute(loc, size, offset)
            offset += size
        }
    }

    override fun resize(width: Int, height: Int, density: Float) {
        if (width == screenW && height == screenH && density == this.density && sim != null) return
        screenW = width
        screenH = height
        this.density = density
        build()
    }

    /** A new city at the zoom: laid out for a screen 1/zoom as big (dp), its skyline to the texture. */
    private fun build() {
        layoutDirty = false
        val dp = density * zoom
        val rng = Rng(System.nanoTime().toInt())
        val c = CityLayout.build((screenW / dp).toDouble(), (screenH / dp).toDouble(), rng)
        layout = c
        sim = CitySim(c, rng)
        signArray = FloatArray(CityFrame.signRoom(c))
        glowArray = FloatArray(CityFrame.glowRoom(c))
        signs?.release()
        glows?.release()
        signs = stream(CityFrame.SIGN, signArray.size / CityFrame.SIGN, intArrayOf(4, 4, 4))
        glows = stream(CityFrame.GLOW, glowArray.size / CityFrame.GLOW, intArrayOf(4, 4, 4))
        sendSkyline(c)
        foregroundDirty = true
        targetDirty = true
        dirty = true
    }

    /** The skyline texture: per layer and column, the top there and which tower (RGBA float, read with texelFetch). */
    private fun sendSkyline(c: City) {
        if (skyline == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            skyline = ids[0]
        }
        val data = CityLayout.skyline(c)
        val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(data).position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, skyline)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, MegacityParams.SKYLINE_WIDTH, c.rows * c.layers.size, 0,
            GLES30.GL_RGBA, GLES30.GL_FLOAT, buffer,
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /** The foreground's triangles: position and premultiplied colour per vertex. */
    private fun sendForeground(c: City) {
        foregroundDirty = false
        val data = CityLayout.foreground(c, tones)
        if (shapeVbo == 0) {
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            shapeVbo = ids[0]
            GLES30.glGenVertexArrays(1, ids, 0)
            shapeVao = ids[0]
            GLES30.glBindVertexArray(shapeVao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, shapeVbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, CityLayout.SHAPE * 4, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, CityLayout.SHAPE * 4, 2 * 4)
            GLES30.glBindVertexArray(0)
        }
        val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(data).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, shapeVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES30.GL_STATIC_DRAW)
        shapeCount = data.size / CityLayout.SHAPE
    }

    /** The px per dp drawn at: the screen's (or fewer at a lower quality), times the zoom. */
    private val pxPerDp: Float get() = density * quality.scale(density) * zoom

    override fun render(dt: Float) {
        if (layoutDirty && screenW > 0) build()
        val s = sim ?: return
        val c = layout ?: return
        if (dt > 0f) s.advance(min(dt, MAX_FRAME_STEP))
        if (targetDirty) {
            targetDirty = false
            target.resize(screenW, screenH, quality.scale(density))
            resizeCache()
            foregroundDirty = true
            dirty = true
        }
        if (foregroundDirty) sendForeground(c)
        if (dirty) setUniforms(c)
        val ns = CityFrame.signs(s, tones, glowLevel, signArray, scratch)
        val ng = CityFrame.glows(s, tones, glowLevel, glowArray, scratch)

        // 1. The city: into its cache when a new second has begun (or something changed), then copied.
        val second = floor(s.time)
        if (second != cachedSecond) drawCity(second)
        target.begin()
        // Every pixel is drawn: the clear only spares a tiled GPU loading the last frame.
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_BLEND)
        copy.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cacheTexture)
        empty.drawFullScreen()
        // 2–4. Signs, glows, the foreground: premultiplied (what has no alpha is added).
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        sign.use()
        signs?.let {
            it.load(signArray, ns)
            it.drawQuads()
        }
        glow.use()
        glows?.let {
            it.load(glowArray, ng)
            it.drawQuads()
        }
        shape.use()
        GLES30.glBindVertexArray(shapeVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, shapeCount)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
        target.end()
    }

    /** The city's cache at the target's size (it's copied one to one), drawn again before it's next used. */
    private fun resizeCache() {
        releaseCache()
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        cacheTexture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cacheTexture)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, target.width, target.height)
        // One to one: nearest reads exactly the texel drawn.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glGenFramebuffers(1, ids, 0)
        cacheFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, cacheFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, cacheTexture, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        copy.use()
        GLES30.glUniform1i(copy.u("uFrame"), 0)
        cachedSecond = Float.NaN
    }

    /** Draws the city into its cache, its clock at [second] (whole seconds: windows switch on its edge). */
    private fun drawCity(second: Float) {
        cachedSecond = second
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, cacheFbo)
        GLES30.glViewport(0, 0, target.width, target.height)
        // Every pixel is drawn: the clear only spares a tiled GPU loading the cache's last picture.
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_BLEND)
        city.use()
        GLES30.glUniform1f(timeAt, second)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, skyline)
        empty.drawFullScreen()
    }

    private fun releaseCache() {
        if (cacheFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(cacheFbo), 0)
        if (cacheTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(cacheTexture), 0)
        cacheFbo = 0
        cacheTexture = 0
    }

    /** Uniforms that hold until the screen, palette, glow, quality or zoom changes (each draws the city's cache again). */
    private fun setUniforms(c: City) {
        dirty = false
        cachedSecond = Float.NaN
        val p = MegacityParams
        city.use()
        GLES30.glUniform1i(city.u("uSkyline"), 0)
        GLES30.glUniform1i(city.u("uLayers"), p.LAYERS.size)
        GLES30.glUniform1i(city.u("uRows"), c.rows)
        GLES30.glUniform1i(city.u("uCols"), c.cols)
        GLES30.glUniform2f(city.u("uTarget"), target.width.toFloat(), target.height.toFloat())
        GLES30.glUniform1f(city.u("uDensity"), pxPerDp)
        GLES30.glUniform1f(city.u("uHeight"), c.h.toFloat())
        GLES30.glUniform2fv(city.u("uCell"), p.LAYERS.size, FloatArray(p.LAYERS.size * 2) { if (it % 2 == 0) p.LAYERS[it / 2].cellW else p.LAYERS[it / 2].cellH }, 0)
        GLES30.glUniform1fv(city.u("uFog"), p.LAYERS.size, FloatArray(p.LAYERS.size) { p.LAYERS[it].fog }, 0)
        GLES30.glUniform3fv(city.u("uSkyTop"), 1, tones.skyTop, 0)
        GLES30.glUniform3fv(city.u("uSkyMid"), 1, tones.skyMid, 0)
        GLES30.glUniform3fv(city.u("uSkyLow"), 1, tones.skyLow, 0)
        GLES30.glUniform3fv(city.u("uLowGlow"), 1, tones.lowGlow, 0)
        GLES30.glUniform3fv(city.u("uFaceFar"), 1, tones.faceFar, 0)
        GLES30.glUniform3fv(city.u("uFaceNear"), 1, tones.faceNear, 0)
        GLES30.glUniform3fv(city.u("uEdge"), 1, tones.edge, 0)
        GLES30.glUniform3fv(city.u("uWinPale"), 1, tones.winPale, 0)
        GLES30.glUniform3fv(city.u("uWinViolet"), 1, tones.winViolet, 0)
        GLES30.glUniform3fv(city.u("uWinPink"), 1, tones.winPink, 0)
        GLES30.glUniform3fv(city.u("uWinWhite"), 1, tones.winWhite, 0)
        // LOW quality leaves out the windows' halo (the city's widest per-pixel extra).
        GLES30.glUniform1f(city.u("uWindowGlow"), if (quality.linkGlow) p.GLOW.getValue(glowLevel).window else 0f)
        sign.use()
        GLES30.glUniform2f(sign.u("uTarget"), target.width.toFloat(), target.height.toFloat())
        GLES30.glUniform1f(sign.u("uDensity"), pxPerDp)
        glow.use()
        GLES30.glUniform2f(glow.u("uTarget"), target.width.toFloat(), target.height.toFloat())
        GLES30.glUniform1f(glow.u("uDensity"), pxPerDp)
        shape.use()
        GLES30.glUniform2f(shape.u("uTarget"), target.width.toFloat(), target.height.toFloat())
        GLES30.glUniform1f(shape.u("uDensity"), pxPerDp)
    }

    override fun setPalette(colors: PaletteColors) {
        this.colors = colors
        tones = CityLayout.tones(colors)
        foregroundDirty = true
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
        if (sim != null) layoutDirty = true
    }

    override fun release() {
        if (skyline != 0) GLES30.glDeleteTextures(1, intArrayOf(skyline), 0)
        if (shapeVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(shapeVbo), 0)
        if (shapeVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(shapeVao), 0)
        skyline = 0
        shapeVbo = 0
        shapeVao = 0
        signs?.release()
        glows?.release()
        releaseCache()
        copy.release()
        target.release()
        empty.release()
        city.release()
        sign.release()
        glow.release()
        shape.release()
        sim = null
        layout = null
    }

    private companion object {
        /** The longest step the motion takes in one frame (s). */
        const val MAX_FRAME_STEP = 0.1f
    }
}
