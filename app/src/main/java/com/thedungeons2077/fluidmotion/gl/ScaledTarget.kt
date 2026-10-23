/*
 * gl/ScaledTarget.kt: where a scene draws: straight to the screen at full
 * resolution, or (QUALITY below the screen's) into a smaller frame that one
 * pass then scales up to the screen.
 *
 * Layer: gl. Used by the scenes that draw straight to the screen (NEURAL
 * NET, VELOCITY); the rain scales its own trail layer. At full resolution
 * it adds nothing: no frame, no extra pass. The frame has an alpha
 * channel, as the screen does (VELOCITY slides its background under its
 * light by destination alpha).
 */
package com.thedungeons2077.fluidmotion.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import kotlin.math.roundToInt

/** A scene's drawing target at a resolution scale. */
class ScaledTarget(assets: AssetManager) {
    private val upscale = GlProgram(assets, "fullscreen.vert", "upscale.frag")
    private val empty = EmptyVao()
    private var fbo = 0
    private var texture = 0
    private var screenW = 0
    private var screenH = 0

    /** The size drawn at (px): the screen's, or the smaller frame's. */
    var width = 0
        private set
    var height = 0
        private set

    /** Drawing into a smaller frame (scaled up after). */
    val scaled: Boolean get() = fbo != 0

    /** Sets up for a screen of [w] × [h] px drawn at [scale] of its resolution (1: straight to the screen). Context current. */
    fun resize(w: Int, h: Int, scale: Float) {
        releaseFrame()
        screenW = w
        screenH = h
        width = w
        height = h
        if (scale >= 0.999f) return
        width = (w * scale).roundToInt().coerceAtLeast(1)
        height = (h * scale).roundToInt().coerceAtLeast(1)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, width, height)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        upscale.use()
        GLES30.glUniform1i(upscale.u("uFrame"), 0)
    }

    /** Binds the target for the scene's drawing (the frame, or the screen). */
    fun begin() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, width, height)
    }

    /** The scene is drawn: if it went into the frame, scales it up to the screen. */
    fun end() {
        if (!scaled) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenW, screenH)
        GLES30.glDisable(GLES30.GL_BLEND)
        // Every pixel is drawn: the clear only spares a tiled GPU loading the last frame.
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        upscale.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        empty.drawFullScreen()
    }

    /** Frees it all (context current). */
    fun release() {
        releaseFrame()
        upscale.release()
        empty.release()
    }

    private fun releaseFrame() {
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        fbo = 0
        texture = 0
    }
}
