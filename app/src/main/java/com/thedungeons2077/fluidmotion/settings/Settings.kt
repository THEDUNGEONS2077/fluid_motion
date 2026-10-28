/*
 * settings/Settings.kt: what the user chose, kept in SharedPreferences
 * ("fluid_motion"): each wallpaper's palette and glow, the power choices
 * (AUTO, calibrated for the phone, or MANUAL: a profile and a QUALITY),
 * the app's own colours, which wallpaper the app is showing.
 *
 * Layer: settings (Android). The wallpapers run in the app's process, so a
 * change made in the app reaches a running wallpaper straight away
 * (observe). Keys are stored: never rename one.
 */
package com.thedungeons2077.fluidmotion.settings

import android.content.Context
import android.content.SharedPreferences
import com.thedungeons2077.fluidmotion.lib.Calibration
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.Palette
import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.PowerChoice
import com.thedungeons2077.fluidmotion.lib.PowerMode
import com.thedungeons2077.fluidmotion.lib.PowerProfile
import com.thedungeons2077.fluidmotion.lib.Quality
import com.thedungeons2077.fluidmotion.lib.Zoom
import com.thedungeons2077.fluidmotion.scene.SceneKind

/** The app's settings. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Writes in the background ([apply]); calls aren't chained (some stand-ins, as the screenshot renderer's, return null from them). */
    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        editor.block()
        editor.apply()
    }

    init {
        // The mode, fixed on first use: a phone that chose a profile by hand before AUTO existed (0.2) stays
        // MANUAL, so its rate never changes by itself; a new one starts in AUTO.
        if (!prefs.contains("power.mode")) {
            write { putString("power.mode", (if (prefs.contains("power.profile")) PowerMode.MANUAL else PowerMode.AUTO).name) }
        }
    }

    /** AUTO (calibrated for this phone) or MANUAL (a profile and a quality). */
    var mode: PowerMode
        get() = PowerMode.find(prefs.getString("power.mode", null), PowerMode.AUTO)
        set(value) = write { putString("power.mode", value.name) }

    /** QUALITY when MANUAL (all wallpapers). */
    var manualQuality: Quality
        get() = Quality.find(prefs.getString("quality.manual", null))
        set(value) = write { putString("quality.manual", value.name) }

    /** What AUTO CALIBRATE chose, and on what (null until it has run). */
    var calibrated: Calibrated?
        get() {
            val profile = prefs.getString("auto.profile", null) ?: return null
            val overall = PowerProfile.find(profile)
            return Calibrated(
                qualities = SceneKind.entries.associateWith { Quality.find(prefs.getString("auto.quality.${it.id}", null)) },
                // Each wallpaper's own rate (0.11.0); a calibration from before has one for all.
                profiles = SceneKind.entries.associateWith { k -> prefs.getString("auto.profile.${k.id}", null)?.let { PowerProfile.find(it) } ?: overall },
                profile = overall,
                device = prefs.getString("auto.device", "") ?: "",
                at = prefs.getLong("auto.at", 0L),
            )
        }
        set(value) = write {
            if (value == null) {
                remove("auto.profile")
            } else {
                for ((kind, q) in value.qualities) putString("auto.quality.${kind.id}", q.name)
                for ((kind, r) in value.profiles) putString("auto.profile.${kind.id}", r.name)
                putString("auto.profile", value.profile.name)
                putString("auto.device", value.device)
                putLong("auto.at", value.at)
            }
        }

    /** The power choices in force for [kind]: AUTO's calibrated profile for it (BALANCED until calibrated), or the hand-picked one. */
    fun effectivePower(kind: SceneKind): PowerChoice {
        val choice = power
        return if (mode == PowerMode.AUTO) choice.copy(profile = calibrated?.profiles?.get(kind) ?: Calibration.PROFILE) else choice
    }

    /** A wallpaper's quality in force: AUTO's calibrated one (HIGH until calibrated), or MANUAL's. */
    fun quality(kind: SceneKind): Quality =
        if (mode == PowerMode.AUTO) calibrated?.qualities?.get(kind) ?: Quality.DEFAULT else manualQuality

    /** A wallpaper's palette (its reference's, until chosen). */
    fun palette(kind: SceneKind): Palette = Palettes.find(prefs.getString("palette.${kind.id}", kind.defaultPalette))

    fun setPalette(kind: SceneKind, palette: Palette) = write { putString("palette.${kind.id}", palette.id) }

    /** A wallpaper's glow level. */
    fun glow(kind: SceneKind): GlowLevel = GlowLevel.find(prefs.getString("glow.${kind.id}", null))

    fun setGlow(kind: SceneKind, glow: GlowLevel) = write { putString("glow.${kind.id}", glow.name) }

    /** A wallpaper's zoom (lib/Zoom.kt): 100% until chosen. */
    fun zoom(kind: SceneKind): Float = Zoom.nearest(prefs.getFloat("zoom.${kind.id}", Zoom.DEFAULT))

    /** Sets a wallpaper's zoom (a step; 100% leaves nothing stored, as the web does). */
    fun setZoom(kind: SceneKind, zoom: Float) = write {
        val step = Zoom.nearest(zoom)
        if (step == Zoom.DEFAULT) remove("zoom.${kind.id}") else putFloat("zoom.${kind.id}", step)
    }

    /** The power choices (all wallpapers); its profile is MANUAL's (effectivePower() is what's in force). */
    var power: PowerChoice
        get() = PowerChoice(
            profile = PowerProfile.find(prefs.getString("power.profile", null)),
            stillInSaver = prefs.getBoolean("power.stillInSaver", true),
            coolWhenHot = prefs.getBoolean("power.coolWhenHot", true),
        )
        set(value) = write {
            putString("power.profile", value.profile.name)
            putBoolean("power.stillInSaver", value.stillInSaver)
            putBoolean("power.coolWhenHot", value.coolWhenHot)
        }

    /** The app's own colours. */
    var uiPalette: Palette
        get() = Palettes.find(prefs.getString("ui.palette", null))
        set(value) = write { putString("ui.palette", value.id) }

    /** What the last AUTO CALIBRATE measured, each wallpaper at each quality (lib/CalibrationSummary.kt's stored form; empty until a 0.11.0 one has run). */
    var calibrationSamples: String
        get() = prefs.getString("auto.samples", "") ?: ""
        set(value) = write { putString("auto.samples", value) }

    /** What the last AUTO CALIBRATE measured, as text (COPY DIAGNOSTICS; empty until one has run). */
    var calibrationReport: String
        get() = prefs.getString("auto.report", "") ?: ""
        set(value) = write { putString("auto.report", value) }

    /**
     * What MEASURE IMPACT found each wallpaper adds at each rate on this
     * phone ("matrix-rain@30=34.2;…": lib/Usage.kt CostModel), the latest
     * per wallpaper and rate: INSIGHT's estimates use it.
     */
    var impactModel: String
        get() = prefs.getString("impact.model", "") ?: ""
        set(value) = write { putString("impact.model", value) }

    /** The last MEASURE IMPACT report (empty until one has run). */
    var impactReport: String
        get() = prefs.getString("impact.report", "") ?: ""
        set(value) = write { putString("impact.report", value) }

    /** A wallpaper's latest MEASURE IMPACT report (COPY BATTERY DATA; kept from 0.7.1, empty until one has run on it). */
    fun impactReport(kind: SceneKind): String = prefs.getString("impact.report.${kind.id}", "") ?: ""

    fun setImpactReport(kind: SceneKind, report: String) = write { putString("impact.report.${kind.id}", report) }

    /** The wallpaper the app shows and sets. */
    var selected: SceneKind
        get() = SceneKind.find(prefs.getString("ui.selected", null))
        set(value) = write { putString("ui.selected", value.id) }

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

/**
 * AUTO CALIBRATE's result: each wallpaper's quality and its own rate
 * ([profiles], 0.11.0: one wallpaper over budget no longer slows them all
 * to ECO), [profile] the slowest of them (for a line about it), the phone
 * it measured ([device]: system build and screen, so a system update or a
 * new resolution can ask for a new calibration), and when.
 */
data class Calibrated(
    val qualities: Map<SceneKind, Quality>,
    val profiles: Map<SceneKind, PowerProfile>,
    val profile: PowerProfile,
    val device: String,
    val at: Long,
)
