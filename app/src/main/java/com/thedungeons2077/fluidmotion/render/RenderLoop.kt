/*
 * render/RenderLoop.kt: draws one scene into one surface, on a thread of
 * its own, as rarely as the motion allows: the heart of the battery work.
 *
 * Layer: render (Android: EGL, Choreographer, threads). Used by the home
 * screen wallpaper (wallpaper/FluidWallpaperService.kt) and the app's
 * preview (ui/PreviewSurface.kt) alike, so the preview is honest.
 *
 * How it keeps the cost down (user request, 2026-10-23: battery and
 * performance first):
 *   - nothing at all while the surface isn't on screen: no frames, no
 *     timers, the thread asleep on its looper (setVisible);
 *   - one wake-up per drawn frame: Choreographer.postFrameCallbackDelayed
 *     sleeps until just before the frame is due and then lands it on a
 *     vsync (no polling every vsync, no judder);
 *   - a capped rate (lib/Power.kt: 20/30/60, halved when warm), told to
 *     the display (Surface.setFrameRate, Android 11+), so an adaptive
 *     panel like the S25 Ultra's can run slower too; rate 0 draws one still
 *     frame and stops (battery saver, hot);
 *   - near the phone's thermal limit (its headroom, read every ten seconds
 *     between frames drawn anyway) a fast rate eases to 30 before anything
 *     gets hot enough to stutter (lib/Power.kt Headroom);
 *   - its thread runs at background priority (the system puts it on the
 *     efficiency cores and gives the app in front the CPU first), its GL
 *     context at low GPU priority (gl/EglCore.kt);
 *   - every frame's work is the scene's, which allocates nothing; what it
 *     costs is measured (FrameMeter: CPU and GPU time, late frames) and
 *     reported once a second.
 * All GL work happens on this loop's thread; the public methods post to it
 * (the ones that must finish before the caller goes on wait for it).
 */
package com.thedungeons2077.fluidmotion.render

import android.content.Context
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import com.thedungeons2077.fluidmotion.gl.EglCore
import com.thedungeons2077.fluidmotion.gl.GL_TAG
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.Headroom
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.Quality
import com.thedungeons2077.fluidmotion.scene.Scene
import com.thedungeons2077.fluidmotion.scene.SceneKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What a loop reports about its drawing (the app's STATUS line, MEASURE IMPACT, COPY DIAGNOSTICS). */
data class FrameStats(
    /** The rate it's asked to draw at (0: a still frame), after any easing for heat. */
    val fps: Int,
    /** What the last second's frames cost (FrameMeter): rate achieved, CPU and GPU ms, late share. */
    val cost: FrameCost,
    /** Frames drawn since it started. */
    val frames: Long,
    /** The GPU took the low priority request. */
    val lowPriority: Boolean,
    /** Eased off from a fast rate because the phone is near its thermal limit (lib/Power.kt Headroom). */
    val eased: Boolean,
    /** The phone's thermal headroom at the last reading (NaN: unknown). */
    val headroom: Float,
    /** Why the scene couldn't be drawn, if it couldn't (it shows black instead). */
    val failure: String? = null,
)

/** Draws [kind] into one surface at a time; [name] tags its thread. */
class RenderLoop(private val context: Context, kind: SceneKind, name: String) {
    private val thread = HandlerThread("fluid_motion-$name", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)

    // Everything below is touched on the loop's thread only.
    private var kind = kind
    private var egl: EglCore? = null
    private var surface: Surface? = null
    private var eglSurface: EGLSurface? = null
    private var scene: Scene? = null
    private var choreographer: Choreographer? = null
    private var width = 0
    private var height = 0
    private var visible = false
    private var fps = 30
    private var colors: PaletteColors = Palettes.PHOSPHOR.colors
    private var glow = GlowLevel.DEFAULT
    private var quality = Quality.DEFAULT
    private var scheduled = false
    private var lastFrameNs = 0L
    private var frames = 0L
    private var failure: String? = null
    private var released = false
    private val meter = FrameMeter()
    private var meterReady = false
    private var cool = true
    private var eased = false
    private var headroom = Float.NaN
    private var headroomReadNs = 0L
    private val power = context.getSystemService(PowerManager::class.java)

    /** About the GPU and surface (COPY DIAGNOSTICS), set once a context exists. */
    val info: String get() = "$gpuInfo\n$surfaceInfo"

    @Volatile
    private var gpuInfo = "no GL context yet"

    @Volatile
    private var surfaceInfo = "no surface yet"

    /** Called (on the loop's thread) about once a second while drawing, and on every change of rate. */
    @Volatile
    var onStats: ((FrameStats) -> Unit)? = null

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        scheduled = false
        drawFrame(frameTimeNanos)
        schedule()
    }

    /** A new surface to draw into (it starts drawing once it has a size). */
    fun surfaceCreated(surface: Surface) = post {
        if (released) return@post
        this.surface = surface
        try {
            val e = egl ?: EglCore().also { egl = it }
            eglSurface = e.createWindowSurface(surface)
            e.makeCurrent(eglSurface!!)
            if (!meterReady) {
                meter.setUp()
                meterReady = true
                gpuInfo = "GPU ${GLES30.glGetString(GLES30.GL_RENDERER)} · ${GLES30.glGetString(GLES30.GL_VERSION)} · " +
                    "GPU timer ${if (meter.gpuTimer) "yes" else "no"} · low priority ${if (e.lowPriority) "yes" else "no"}"
            }
        } catch (e: IllegalStateException) {
            // No GL here at all (it can't draw): say why rather than crash the wallpaper over and over.
            Log.e(GL_TAG, "no GL surface", e)
            eglSurface = null
            failure = e.message ?: "no GL"
            report()
            return@post
        }
        if (choreographer == null) choreographer = Choreographer.getInstance()
        if (scene == null) scene = createScene()
        applyRateHint()
    }

    /** The surface's size (px). */
    fun surfaceChanged(width: Int, height: Int) = post {
        this.width = width
        this.height = height
        surfaceInfo = "surface $width × $height px at ${context.resources.displayMetrics.density} px/dp"
        val s = scene ?: return@post
        eglSurface ?: return@post
        s.resize(width, height, context.resources.displayMetrics.density)
        drawStill()
        schedule()
    }

    /** Draws a frame now, waiting until it is on the surface (the system asks before showing a new surface). */
    fun redraw() = runAndWait { drawStill() }

    /** The surface is going away: stop drawing into it before it does (waits). */
    fun surfaceDestroyed() = runAndWait {
        choreographer?.removeFrameCallback(frameCallback)
        scheduled = false
        eglSurface?.let { egl?.releaseSurface(it) }
        eglSurface = null
        surface = null
    }

    /** On screen or not: off screen, nothing runs at all. */
    fun setVisible(visible: Boolean) = post {
        if (this.visible == visible) return@post
        this.visible = visible
        if (visible) {
            // Carry on from where it stopped (no leap over the time away).
            lastFrameNs = 0L
            if (fps == 0) drawStill()
            schedule()
        } else {
            choreographer?.removeFrameCallback(frameCallback)
            scheduled = false
        }
    }

    /**
     * The frame rate (0: one still frame, then nothing until something
     * changes), and whether to ease off near the thermal limit ([cool]: the
     * COOL DOWN WHEN HOT setting).
     */
    fun setRate(fps: Int, cool: Boolean = true) = post {
        if (!cool) eased = false
        if (this.fps == fps && this.cool == cool) return@post
        this.fps = fps
        this.cool = cool
        applyRateHint()
        choreographer?.removeFrameCallback(frameCallback)
        scheduled = false
        lastFrameNs = 0L
        if (fps == 0) drawStill() else schedule()
        report()
    }

    /** Colours, glow and quality (a still wallpaper redraws to show them). */
    fun setLook(colors: PaletteColors, glow: GlowLevel, quality: Quality) = post {
        this.colors = colors
        this.glow = glow
        this.quality = quality
        scene?.let {
            it.setPalette(colors)
            it.setGlow(glow)
            it.setQuality(quality)
        }
        if (fps == 0) drawStill()
    }

    /** Calibrating: where the GPU can't be timed, frames are timed to their end instead (FrameMeter.sync). */
    fun setCalibrating(on: Boolean) = post {
        meter.sync = on
    }

    /** Another wallpaper in the same surface (the app's preview switching). */
    fun setKind(kind: SceneKind) = post {
        if (this.kind == kind) return@post
        this.kind = kind
        val e = egl ?: return@post
        val es = eglSurface ?: return@post
        e.makeCurrent(es)
        scene?.release()
        scene = createScene()?.also {
            if (width > 0) it.resize(width, height, context.resources.displayMetrics.density)
        }
        lastFrameNs = 0L
        drawStill()
        schedule()
    }

    /** Stops for good: frees the GL side and the thread (waits). */
    fun release() {
        runAndWait {
            released = true
            choreographer?.removeFrameCallback(frameCallback)
            val e = egl
            if (e != null) {
                eglSurface?.let { e.makeCurrent(it) }
                // GL objects can only be freed with the context current; without a surface they go with the context.
                if (eglSurface != null) {
                    scene?.release()
                    meter.release()
                }
                eglSurface?.let { e.releaseSurface(it) }
                e.release()
            }
            scene = null
            egl = null
            eglSurface = null
        }
        thread.quitSafely()
    }

    /**
     * The scene, set up in the current colours; null if it can't be built
     * on this GPU (a driver refusing a shader): the surface then shows black
     * and the stats say why, instead of the wallpaper crashing over and over.
     */
    private fun createScene(): Scene? = try {
        failure = null
        kind.create(context).also {
            it.setPalette(colors)
            it.setGlow(glow)
            it.setQuality(quality)
        }
    } catch (e: RuntimeException) {
        Log.e(GL_TAG, "can't draw ${kind.id}", e)
        failure = e.message ?: e.javaClass.simpleName
        report()
        null
    }

    /** The rate it draws at now: the one asked for, eased if the phone is near its thermal limit. */
    private val rate: Int get() = Headroom.rate(fps, eased)

    /** Asks for the next frame, timed to land on the vsync nearest its due time. */
    private fun schedule() {
        val c = choreographer ?: return
        if (scheduled || !visible || eglSurface == null || scene == null || rate <= 0 || width == 0) return
        scheduled = true
        val now = System.nanoTime()
        val due = if (lastFrameNs == 0L) now else lastFrameNs + 1_000_000_000L / rate - VSYNC_SLACK_NS
        c.postFrameCallbackDelayed(frameCallback, ((due - now) / 1_000_000L).coerceAtLeast(0L))
    }

    /** Draws one frame, the scene moved on by the time since the last. */
    private fun drawFrame(frameTimeNanos: Long) {
        val s = scene ?: return
        val es = eglSurface ?: return
        val e = egl ?: return
        val interval = 1_000_000_000L / rate.coerceAtLeast(1)
        // Late: more than half an interval after it was due (a frame the eye may catch as a stutter).
        val late = lastFrameNs != 0L && frameTimeNanos - lastFrameNs > interval + interval / 2
        val dt = if (lastFrameNs == 0L) 0f else (frameTimeNanos - lastFrameNs) / 1e9f
        lastFrameNs = frameTimeNanos
        meter.begin(late)
        s.render(dt)
        meter.endGpu()
        if (!e.swap(es)) Log.w(GL_TAG, "swap failed: surface gone")
        meter.end()
        frames++
        // About once a second: the stats; every ten seconds or so, the thermal headroom.
        if (frames % rate.coerceAtLeast(1) == 0L) report()
        if (frameTimeNanos - headroomReadNs >= Headroom.READ_EVERY_NS) readHeadroom(frameTimeNanos)
    }

    /**
     * Reads the thermal headroom (Android 11+) and eases off a fast rate if
     * the phone is getting near its limit. Read here, between frames that
     * are drawn anyway, so it never wakes the phone by itself.
     */
    private fun readHeadroom(now: Long) {
        headroomReadNs = now
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        headroom = power?.getThermalHeadroom(HEADROOM_FORECAST_S) ?: Float.NaN
        val next = Headroom.eased(headroom, cool, eased)
        if (next == eased) return
        eased = next
        applyRateHint()
        report()
    }

    /** Draws the scene as it is (no time passes): a still wallpaper's frame, or a first frame. */
    private fun drawStill() {
        val es = eglSurface ?: return
        if (width == 0) return
        val s = scene
        if (s != null) {
            s.render(0f)
        } else {
            // No scene (it failed): black, not whatever the surface held.
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        egl?.swap(es)
        frames++
    }

    /** Tells the display what rate this surface wants (Android 11+): 0 is "no preference". */
    private fun applyRateHint() {
        val sf = surface ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !sf.isValid) return
        try {
            sf.setFrameRate(rate.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        } catch (e: IllegalArgumentException) {
            Log.w(GL_TAG, "setFrameRate($rate) refused", e)
        } catch (e: IllegalStateException) {
            Log.w(GL_TAG, "setFrameRate($rate) on a released surface", e)
        }
    }

    /** Sends the stats, then starts the measurements over. */
    private fun report() {
        onStats?.invoke(FrameStats(rate, meter.take(), frames, egl?.lowPriority == true, eased, headroom, failure))
    }

    /** Runs [block] on the loop's thread, later (the caller goes on). */
    private fun post(block: () -> Unit) {
        handler.post(block)
    }

    /** Runs [block] on the loop's thread and waits for it (two seconds at most: never hang the caller). */
    private fun runAndWait(block: () -> Unit) {
        if (Thread.currentThread() == thread) {
            block()
            return
        }
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        if (posted && !done.await(WAIT_SECONDS, TimeUnit.SECONDS)) Log.w(GL_TAG, "render thread slow to answer")
    }

    private companion object {
        /** Asks for a frame this early (ns): the Choreographer then lands it on the vsync at its due time. */
        const val VSYNC_SLACK_NS = 4_000_000L

        /** The longest a caller waits on the loop's thread. */
        const val WAIT_SECONDS = 2L

        /** How far ahead the thermal headroom is forecast (s): far enough to ease off in time. */
        const val HEADROOM_FORECAST_S = 10
    }
}
