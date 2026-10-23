/*
 * gl/EglCore.kt: an OpenGL ES 3 context of our own (EGL14), and the window
 * surfaces it draws to.
 *
 * Layer: gl (Android EGL). Each render loop (render/RenderLoop.kt) owns one,
 * on its own thread. Two choices here serve the battery objective:
 *   - the context asks for LOW GPU priority where the driver offers it
 *     (EGL_IMG_context_priority, Adreno and Mali do): the launcher, the
 *     compositor and the app in front always get the GPU first;
 *   - no depth or stencil buffers (nothing here needs them): smaller
 *     surfaces, less memory traffic per frame.
 * The surface keeps an alpha channel: VELOCITY lays its light down first
 * and slides the background under it by destination alpha (as the web's
 * canvas over its CSS background). The window stays opaque to the
 * compositor (WallpaperService asks for an opaque window; the in-app
 * preview asks for RGBX), so the alpha costs nothing there.
 */
package com.thedungeons2077.fluidmotion.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface

/** An EGL display and an ES 3 context, made current on the thread that creates it. */
class EglCore {
    /** The display connection. */
    val display: EGLDisplay
    /** The chosen framebuffer config: RGBA8888, no depth, no stencil. */
    private val config: EGLConfig
    /** The context. */
    val context: EGLContext
    /** True when the driver took the low priority request. */
    val lowPriority: Boolean

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed: ${error()}" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(8)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, configs.size, count, 0) && count[0] > 0) { "no ES 3 config: ${error()}" }
        // eglChooseConfig sorts deeper buffers first: take the first with exactly what we asked (no depth sneaking in).
        config = (0 until count[0]).map { configs[it]!! }.firstOrNull { attr(it, EGL14.EGL_DEPTH_SIZE) == 0 && attr(it, EGL14.EGL_ALPHA_SIZE) == 8 } ?: configs[0]!!
        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: ""
        val canLower = "EGL_IMG_context_priority" in extensions
        val contextAttribs = if (canLower) {
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, CONTEXT_PRIORITY_LEVEL_IMG, CONTEXT_PRIORITY_LOW_IMG, EGL14.EGL_NONE)
        } else {
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        }
        var ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (ctx == EGL14.EGL_NO_CONTEXT && canLower) {
            // A driver that lists the extension but refuses the level: an ordinary context.
            ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        }
        check(ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${error()}" }
        context = ctx
        // What the driver actually gave (it may quietly ignore the request).
        val got = IntArray(1)
        lowPriority = canLower && EGL14.eglQueryContext(display, context, CONTEXT_PRIORITY_LEVEL_IMG, got, 0) && got[0] == CONTEXT_PRIORITY_LOW_IMG
    }

    /** A window surface on [nativeWindow] (a Surface or SurfaceHolder). */
    fun createWindowSurface(nativeWindow: Any): EGLSurface {
        val surface = EGL14.eglCreateWindowSurface(display, config, nativeWindow, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${error()}" }
        return surface
    }

    /** Makes [surface] current for drawing and reading. */
    fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed: ${error()}" }
    }

    /** Shows the frame drawn on [surface]; false if the surface is gone (EGL_BAD_SURFACE: the window went away). */
    fun swap(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    /** A surface's size in px: [width, height]. */
    fun size(surface: EGLSurface, out: IntArray) {
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, out, 0)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, out, 1)
    }

    /** Lets a surface go (detaching it first if it is current). */
    fun releaseSurface(surface: EGLSurface) {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
    }

    /**
     * Frees the context (call last, on the same thread). The display stays
     * up: it's the process's, shared by every loop (home screen, lock
     * screen, the app's preview), and terminating it under another loop
     * would pull its context away.
     */
    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }

    /** A config attribute. */
    private fun attr(c: EGLConfig, name: Int): Int {
        val v = IntArray(1)
        EGL14.eglGetConfigAttrib(display, c, name, v, 0)
        return v[0]
    }

    /** The last EGL error, as hex. */
    private fun error(): String = "0x" + Integer.toHexString(EGL14.eglGetError())

    private companion object {
        /** EGL_IMG_context_priority's names (not in EGL14). */
        const val CONTEXT_PRIORITY_LEVEL_IMG = 0x3100
        const val CONTEXT_PRIORITY_LOW_IMG = 0x3103
    }
}
