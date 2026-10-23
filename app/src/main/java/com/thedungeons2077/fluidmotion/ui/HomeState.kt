/*
 * ui/HomeState.kt: what the home screen shows and changes: the chosen
 * wallpaper and its look, the power choices, the app's colours, and the
 * preview's state. Every change is saved at once (settings/Settings.kt),
 * which a running wallpaper hears straight away, and sent to the preview.
 *
 * Layer: ui (Compose state, main thread).
 */
package com.thedungeons2077.fluidmotion.ui

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.Palette
import com.thedungeons2077.fluidmotion.lib.PowerChoice
import com.thedungeons2077.fluidmotion.lib.PowerState
import com.thedungeons2077.fluidmotion.lib.frameRate
import com.thedungeons2077.fluidmotion.render.FrameStats
import com.thedungeons2077.fluidmotion.render.RenderLoop
import com.thedungeons2077.fluidmotion.scene.SceneKind
import com.thedungeons2077.fluidmotion.settings.Settings
import com.thedungeons2077.fluidmotion.wallpaper.MatrixRainService
import com.thedungeons2077.fluidmotion.wallpaper.NeuralNetService
import com.thedungeons2077.fluidmotion.wallpaper.VelocityService

/** The home screen's state. */
class HomeState(private val context: Context, private val settings: Settings, private val loop: RenderLoop) {
    /** The wallpaper shown, and its colours and glow. */
    var selected by mutableStateOf(settings.selected)
        private set
    var palette by mutableStateOf(settings.palette(selected))
        private set
    var glow by mutableStateOf(settings.glow(selected))
        private set

    /** The power choices (all wallpapers), and the phone's state. */
    var power by mutableStateOf(settings.power)
        private set
    var powerState by mutableStateOf(PowerState())
        private set

    /** The app's own colours. */
    var uiPalette by mutableStateOf(settings.uiPalette)
        private set

    /** The preview's drawing, as last reported. */
    var stats by mutableStateOf<FrameStats?>(null)

    /** The wallpaper on the home screen now, if it's one of ours. */
    var active by mutableStateOf<SceneKind?>(null)
        private set

    /** The controls showing (a tap on the preview hides them, to see the wallpaper whole). */
    var panelOpen by mutableStateOf(true)

    /** The app in front (the clock only ticks then). */
    var started by mutableStateOf(false)

    init {
        applyLook()
        applyRate()
    }

    fun select(kind: SceneKind) {
        if (kind == selected) return
        selected = kind
        settings.selected = kind
        palette = settings.palette(kind)
        glow = settings.glow(kind)
        loop.setKind(kind)
        applyLook()
    }

    fun choosePalette(p: Palette) {
        palette = p
        settings.setPalette(selected, p)
        applyLook()
    }

    fun chooseGlow(g: GlowLevel) {
        glow = g
        settings.setGlow(selected, g)
        applyLook()
    }

    fun choosePower(c: PowerChoice) {
        power = c
        settings.power = c
        applyRate()
    }

    fun chooseUiPalette(p: Palette) {
        uiPalette = p
        settings.uiPalette = p
    }

    /** The phone's power state changed (PowerWatch). */
    fun onPower(state: PowerState) {
        powerState = state
        applyRate()
    }

    /** The rate the preview draws at: the same rule as the wallpaper's. */
    val fps: Int get() = frameRate(power, powerState)

    private fun applyLook() = loop.setLook(palette.colors, glow)

    private fun applyRate() = loop.setRate(fps)

    /** Looks up which wallpaper is on the home screen (after the picker, say). */
    fun refreshActive() {
        val info = WallpaperManager.getInstance(context).wallpaperInfo
        active = SceneKind.entries.firstOrNull { info != null && info.packageName == context.packageName && info.serviceName == serviceOf(it).name }
    }

    /**
     * Opens the system's preview of the selected wallpaper, with its SET
     * button (the system asks about home and lock screens). Started from the
     * activity, in its task: setting it comes back here, marked ● ON HOME.
     */
    fun setAsWallpaper() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, serviceOf(selected)))
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // A phone without that screen: the list of live wallpapers, where ours are.
            context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    private fun serviceOf(kind: SceneKind): Class<*> = when (kind) {
        SceneKind.RAIN -> MatrixRainService::class.java
        SceneKind.NEURAL -> NeuralNetService::class.java
        SceneKind.VELOCITY -> VelocityService::class.java
    }
}
