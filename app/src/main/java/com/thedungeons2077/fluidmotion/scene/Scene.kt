/*
 * scene/Scene.kt: what a wallpaper is to the render loop, and the catalog of
 * the three.
 *
 * Layer: scene (GL). A Scene draws one wallpaper into whatever surface is
 * current: the home screen's (wallpaper/FluidWallpaperService.kt) or the
 * app's preview (ui/PreviewSurface.kt), driven by render/RenderLoop.kt.
 * Every method runs on the loop's GL thread with the context current.
 */
package com.thedungeons2077.fluidmotion.scene

import android.content.Context
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.Quality
import com.thedungeons2077.fluidmotion.scene.flow.FlowRenderer
import com.thedungeons2077.fluidmotion.scene.megacity.MegacityRenderer
import com.thedungeons2077.fluidmotion.scene.neural.NeuralRenderer
import com.thedungeons2077.fluidmotion.scene.rain.RainRenderer
import com.thedungeons2077.fluidmotion.scene.velocity.VelocityRenderer

/** One wallpaper's drawing. */
interface Scene {
    /** Sets up for a target of [width] × [height] px at [density] px per dp (the first call builds the scene). */
    fun resize(width: Int, height: Int, density: Float)

    /** Moves the scene on by [dt] seconds (0: just draw it as it is) and draws it. */
    fun render(dt: Float)

    /** New colours (they apply from the next frame; the rain's rain in, as on the web). */
    fun setPalette(colors: PaletteColors)

    /** A new glow level. */
    fun setGlow(glow: GlowLevel)

    /**
     * A new detail level (lib/Quality.kt: mostly the resolution drawn at).
     * Any rebuilding it needs waits for the next frame, when the context is
     * sure to be current.
     */
    fun setQuality(quality: Quality)

    /**
     * A new zoom (lib/Zoom.kt, 1 = as designed): the scene lays itself out
     * for a screen 1/zoom as big and draws it zoom times as large, at the
     * resolution QUALITY sets for the screen (zoomed in, it's as sharp).
     * Like a new quality, it's applied at the next frame.
     */
    fun setZoom(zoom: Float)

    /** Frees its GL objects. */
    fun release()
}

/**
 * The wallpapers: ids (stored in settings: never rename one), names as the
 * dashboard shows them, the palette each starts in (its reference's; DATA
 * FLOW, with no reference, in ICE: cyan and violet), whether QUALITY's
 * [Quality.linkGlow] changes what it draws (NEURAL NET's link glow, DATA
 * FLOW's soft glows and halos, MEGACITY's window halo: AUTO CALIBRATE then
 * measures LOW apart), and
 * how to make its scene.
 */
enum class SceneKind(val id: String, val label: String, val defaultPalette: String, val usesLinkGlow: Boolean) {
    RAIN("matrix-rain", "MATRIX RAIN", "phosphor", false),
    NEURAL("neural-net", "NEURAL NET", "blood", true),
    VELOCITY("velocity", "VELOCITY", "blood", false),
    DATA_FLOW("data-flow", "DATA FLOW", "ice", true),
    MEGACITY("megacity", "MEGACITY", "neon", true);

    /** A new scene of this kind. */
    fun create(context: Context): Scene = when (this) {
        RAIN -> RainRenderer(context)
        NEURAL -> NeuralRenderer(context)
        VELOCITY -> VelocityRenderer(context)
        DATA_FLOW -> FlowRenderer(context)
        MEGACITY -> MegacityRenderer(context)
    }

    companion object {
        /** The kind with [id], or the rain. */
        fun find(id: String?): SceneKind = entries.firstOrNull { it.id == id } ?: RAIN
    }
}
