/*
 * settings/Settings.kt: what the user chose, kept in SharedPreferences
 * ("fluid_motion"): each wallpaper's palette and glow, the power choices,
 * the app's own colours, which wallpaper the app is showing.
 *
 * Layer: settings (Android). The wallpapers run in the app's process, so a
 * change made in the app reaches a running wallpaper straight away
 * (observe). Keys are stored: never rename one.
 */
package com.thedungeons2077.fluidmotion.settings

import android.content.Context
import android.content.SharedPreferences
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.Palette
import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.PowerChoice
import com.thedungeons2077.fluidmotion.lib.PowerProfile
import com.thedungeons2077.fluidmotion.scene.SceneKind

/** The app's settings. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** A wallpaper's palette (its reference's, until chosen). */
    fun palette(kind: SceneKind): Palette = Palettes.find(prefs.getString("palette.${kind.id}", kind.defaultPalette))

    fun setPalette(kind: SceneKind, palette: Palette) = prefs.edit().putString("palette.${kind.id}", palette.id).apply()

    /** A wallpaper's glow level. */
    fun glow(kind: SceneKind): GlowLevel = GlowLevel.find(prefs.getString("glow.${kind.id}", null))

    fun setGlow(kind: SceneKind, glow: GlowLevel) = prefs.edit().putString("glow.${kind.id}", glow.name).apply()

    /** The power choices (all wallpapers). */
    var power: PowerChoice
        get() = PowerChoice(
            profile = PowerProfile.find(prefs.getString("power.profile", null)),
            stillInSaver = prefs.getBoolean("power.stillInSaver", true),
            coolWhenHot = prefs.getBoolean("power.coolWhenHot", true),
        )
        set(value) = prefs.edit()
            .putString("power.profile", value.profile.name)
            .putBoolean("power.stillInSaver", value.stillInSaver)
            .putBoolean("power.coolWhenHot", value.coolWhenHot)
            .apply()

    /** The app's own colours. */
    var uiPalette: Palette
        get() = Palettes.find(prefs.getString("ui.palette", null))
        set(value) = prefs.edit().putString("ui.palette", value.id).apply()

    /** The last MEASURE IMPACT report (empty until one has run). */
    var impactReport: String
        get() = prefs.getString("impact.report", "") ?: ""
        set(value) = prefs.edit().putString("impact.report", value).apply()

    /** The wallpaper the app shows and sets. */
    var selected: SceneKind
        get() = SceneKind.find(prefs.getString("ui.selected", null))
        set(value) = prefs.edit().putString("ui.selected", value.id).apply()

    /**
     * Calls [onChange] (main thread) whenever a setting changes, until the
     * returned handle is closed. The handle holds the listener: keep it
     * (SharedPreferences only keeps listeners weakly, so a dropped one would
     * stop hearing changes at the next garbage collection).
     */
    fun observe(onChange: () -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val FILE = "fluid_motion"
    }
}
