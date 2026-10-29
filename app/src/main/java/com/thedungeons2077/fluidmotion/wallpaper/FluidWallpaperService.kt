/*
 * wallpaper/FluidWallpaperService.kt: the live wallpapers the system runs:
 * one service per wallpaper (so each is its own entry in the phone's
 * wallpaper picker), all the same engine.
 *
 * Layer: wallpaper (Android WallpaperService). An engine is a thin bridge:
 * the system tells it about its surface and whether it's on screen, the
 * settings and PowerWatch tell it what to draw and how often, and a
 * RenderLoop (render/RenderLoop.kt) does the drawing. It never draws while
 * hidden (setVisible), takes no touches (none are needed, so none are
 * delivered), and tells the system its colours itself (onComputeColors)
 * so the system never has to work them out from a screenshot.
 */
package com.thedungeons2077.fluidmotion.wallpaper

import android.app.WallpaperColors
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.thedungeons2077.fluidmotion.lib.PowerState
import com.thedungeons2077.fluidmotion.lib.frameRate
import com.thedungeons2077.fluidmotion.render.PowerWatch
import com.thedungeons2077.fluidmotion.render.RenderLoop
import com.thedungeons2077.fluidmotion.scene.SceneKind
import com.thedungeons2077.fluidmotion.settings.Settings

/** A live wallpaper showing [kind]. */
abstract class FluidWallpaperService : WallpaperService() {
    /** The wallpaper this service shows. */
    abstract val kind: SceneKind

    override fun onCreateEngine(): Engine = FluidEngine()

    /** One running copy of the wallpaper (home screen, lock screen, the picker's preview: one each). */
    inner class FluidEngine : Engine() {
        private lateinit var settings: Settings
        private lateinit var loop: RenderLoop
        private var observer: AutoCloseable? = null
        private var palette = ""
        private val onPower: (PowerState) -> Unit = { applyRate() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // Opaque: the compositor never blends what's behind a wallpaper (there's nothing).
            surfaceHolder.setFormat(PixelFormat.RGBX_8888)
            setTouchEventsEnabled(false)
            settings = Settings(this@FluidWallpaperService)
            // The picker's preview isn't the wallpaper in use: it keeps no INSIGHT log.
            loop = RenderLoop(applicationContext, kind, if (isPreview) "${kind.id}-preview" else kind.id, recordUsage = !isPreview)
            applyLook()
            observer = settings.observe {
                applyLook()
                applyRate()
            }
            PowerWatch.add(this@FluidWallpaperService, onPower)
        }

        /** The wallpaper's palette, glow, quality and zoom, from the settings (and new colours for the system, if they changed). */
        private fun applyLook() {
            val p = settings.palette(kind)
            loop.setLook(p.colors, settings.glow(kind), settings.quality(kind), settings.zoom(kind))
            if (palette.isNotEmpty() && palette != p.id) notifyColorsChanged()
            palette = p.id
        }

        /** The frame rate the settings and the phone's state call for. */
        private fun applyRate() {
            val power = settings.effectivePower(kind)
            loop.setRate(frameRate(power, PowerWatch.state), power.coolWhenHot)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            loop.surfaceCreated(holder.surface)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            loop.surfaceChanged(width, height)
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            loop.redraw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            loop.setVisible(visible)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            loop.surfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            observer?.close()
            observer = null
            PowerWatch.remove(onPower)
            loop.release()
            super.onDestroy()
        }

        /** The wallpaper's colours for the system's theming: the palette's light on a dark wallpaper. */
        override fun onComputeColors(): WallpaperColors {
            val c = settings.palette(kind).colors
            val primary = Color.valueOf(Color.rgb((c.accent shr 16) and 255, (c.accent shr 8) and 255, c.accent and 255))
            val secondary = Color.valueOf(Color.rgb((c.cool shr 16) and 255, (c.cool shr 8) and 255, c.cool and 255))
            val tertiary = Color.valueOf(Color.rgb((c.textBright shr 16) and 255, (c.textBright shr 8) and 255, c.textBright and 255))
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                WallpaperColors(primary, secondary, tertiary, WallpaperColors.HINT_SUPPORTS_DARK_THEME)
            } else {
                WallpaperColors(primary, secondary, tertiary)
            }
        }
    }
}

/** MATRIX RAIN. */
class MatrixRainService : FluidWallpaperService() {
    override val kind = SceneKind.RAIN
}

/** NEURAL NET. */
class NeuralNetService : FluidWallpaperService() {
    override val kind = SceneKind.NEURAL
}

/** VELOCITY. */
class VelocityService : FluidWallpaperService() {
    override val kind = SceneKind.VELOCITY
}

/** DATA FLOW. */
class DataFlowService : FluidWallpaperService() {
    override val kind = SceneKind.DATA_FLOW
}

/** MEGACITY. */
class MegacityService : FluidWallpaperService() {
    override val kind = SceneKind.MEGACITY
}

/** HORIZON. */
class HorizonService : FluidWallpaperService() {
    override val kind = SceneKind.HORIZON
}

/** SUNSET. */
class SunsetService : FluidWallpaperService() {
    override val kind = SceneKind.SUNSET
}

/** PRISM. */
class PrismService : FluidWallpaperService() {
    override val kind = SceneKind.PRISM
}

/** QUANTUM KERNEL. */
class KernelService : FluidWallpaperService() {
    override val kind = SceneKind.QUANTUM_KERNEL
}
