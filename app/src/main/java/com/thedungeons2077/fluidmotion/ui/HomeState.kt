/*
 * ui/HomeState.kt: what the home screen shows and changes: the chosen
 * wallpaper and its look, the power choices, the app's colours, and the
 * preview's state. Every change is saved at once (settings/Settings.kt),
 * which a running wallpaper hears straight away, and sent to the preview.
 * It also runs MEASURE IMPACT (the battery cost of each rate, measured on
 * the phone: lib/Impact.kt) and gathers COPY DIAGNOSTICS.
 *
 * Layer: ui (Compose state, main thread).
 */
package com.thedungeons2077.fluidmotion.ui

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thedungeons2077.fluidmotion.BuildConfig
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.Impact
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Where MEASURE IMPACT is: the step's name, its number of [steps], seconds left in it. */
data class ImpactProgress(val label: String, val step: Int, val steps: Int, val secondsLeft: Int)

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

    /** MEASURE IMPACT in progress (null when not running). */
    var impact by mutableStateOf<ImpactProgress?>(null)
        private set

    /** The last MEASURE IMPACT report, and a note when a run couldn't start or stopped early. */
    var impactReport by mutableStateOf(settings.impactReport)
        private set
    var impactNote by mutableStateOf<String?>(null)
        private set

    private var impactJob: Job? = null

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

    /** Sends the rate to the preview (not while MEASURE IMPACT sets its own). */
    private fun applyRate() {
        if (impactJob == null) loop.setRate(fps, power.coolWhenHot)
    }

    /**
     * Runs MEASURE IMPACT in [scope]: the selected wallpaper full screen at
     * each rate in turn (STILL first), the battery current sampled once a
     * second, the frames' cost from the preview's loop. About three
     * minutes. It needs the phone unplugged (the current is the battery's
     * then), and keeps going only while the app is in front.
     */
    fun startImpact(scope: CoroutineScope) {
        if (impactJob != null) return
        val battery = context.getSystemService(BatteryManager::class.java)
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if ((sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0) {
            impactNote = "UNPLUG THE PHONE FIRST: the battery's current is only the phone's own when it isn't charging."
            return
        }
        if (Impact.milliamps(battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)) == null) {
            impactNote = "THIS PHONE DOESN'T REPORT ITS BATTERY CURRENT: the frame costs in STATUS are what there is."
            return
        }
        impactNote = null
        panelOpen = false
        impactJob = scope.launch {
            var finished = false
            try {
                val results = mutableListOf<Impact.Result>()
                val samples = FloatArray(Impact.STEP_SECONDS)
                Impact.STEPS.forEachIndexed { index, step ->
                    // The raw rate: no easing for heat, so each step is what that rate costs.
                    loop.setRate(step.fps, cool = false)
                    var n = 0
                    var cpu = 0f
                    var gpu = 0f
                    var gpuN = 0
                    var drawn = 0f
                    var costN = 0
                    for (second in 0 until Impact.STEP_SECONDS) {
                        impact = ImpactProgress(step.label, index + 1, Impact.STEPS.size, Impact.STEP_SECONDS - second)
                        delay(1000)
                        if (second < Impact.SETTLE_SECONDS) continue
                        Impact.milliamps(battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))?.let { samples[n++] = it }
                        val cost = stats?.cost
                        if (step.fps > 0 && cost != null) {
                            cpu += cost.cpuMs
                            drawn += cost.fps
                            costN++
                            if (!cost.gpuMs.isNaN()) {
                                gpu += cost.gpuMs
                                gpuN++
                            }
                        }
                    }
                    results += Impact.Result(
                        label = step.label,
                        fps = step.fps,
                        mA = Impact.trimmedMean(samples, n),
                        drawnFps = if (costN > 0) drawn / costN else 0f,
                        cpuMs = if (costN > 0) cpu / costN else 0f,
                        gpuMs = if (gpuN > 0) gpu / gpuN else Float.NaN,
                        tempC = batteryTemperature(),
                    )
                }
                val capacity = Impact.capacityMah(
                    battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                    battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                )
                val report = Impact.report(results, capacity, "${selected.label} · ${palette.label} · GLOW ${glow.label} · ${Build.MANUFACTURER} ${Build.MODEL}")
                impactReport = report
                settings.impactReport = report
                finished = true
            } finally {
                if (!finished) impactNote = "MEASURE IMPACT STOPPED: run it again with the app in front and the phone left alone."
                impact = null
                impactJob = null
                applyRate()
                panelOpen = true
            }
        }
    }

    /** Stops MEASURE IMPACT (the app went to the background, or CANCEL). */
    fun cancelImpact() {
        impactJob?.cancel()
    }

    /** The battery's temperature (°C), from the last battery broadcast; NaN if unknown. */
    private fun batteryTemperature(): Float {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return Float.NaN
        val tenths = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) Float.NaN else tenths / 10f
    }

    /** Everything worth knowing to tune the wallpapers on this phone, copied for pasting (COPY DIAGNOSTICS). */
    fun copyDiagnostics() {
        val s = stats
        val modes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.supportedModes?.map { "%.0f".format(Locale.UK, it.refreshRate) }?.distinct()?.joinToString("/") ?: "?"
        } else {
            "?"
        }
        val text = listOf(
            "fluid_motion ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · display Hz $modes",
            loop.info,
            "wallpaper ${selected.label} · ${palette.label} · GLOW ${glow.label} · ${power.profile.label} (${power.profile.fps} fps)" +
                " · still in saver ${power.stillInSaver} · cool when hot ${power.coolWhenHot}",
            "phone: battery saver ${powerState.powerSave} · thermal status ${powerState.thermal}",
            if (s == null) {
                "frames: none measured yet"
            } else {
                "frames: asked %d fps · drawn %.1f fps · CPU %.2f ms · GPU %s · late %.0f%% · eased %s · headroom %s".format(
                    Locale.UK, s.fps, s.cost.fps, s.cost.cpuMs,
                    if (s.cost.gpuMs.isNaN()) "n/a" else "%.2f ms".format(Locale.UK, s.cost.gpuMs),
                    s.cost.late * 100, s.eased, if (s.headroom.isNaN()) "n/a" else "%.2f".format(Locale.UK, s.headroom),
                ) + (s.failure?.let { " · FAILED: $it" } ?: "")
            },
            impactReport.ifEmpty { "MEASURE IMPACT: not run yet" },
        ).joinToString("\n")
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("fluid_motion diagnostics", text))
    }

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
