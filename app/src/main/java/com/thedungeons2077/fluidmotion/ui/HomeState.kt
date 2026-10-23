/*
 * ui/HomeState.kt: what the home screen shows and changes: the chosen
 * wallpaper and its look, the power choices, the app's colours, and the
 * preview's state. Every change is saved at once (settings/Settings.kt),
 * which a running wallpaper hears straight away, and sent to the preview.
 * It also runs AUTO CALIBRATE (each wallpaper's quality chosen from its
 * frames' measured cost: lib/Quality.kt), MEASURE IMPACT (the battery cost
 * of each rate, measured on the phone: lib/Impact.kt), shows INSIGHT (the
 * wallpapers' week: lib/Usage.kt), and gathers COPY DIAGNOSTICS.
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
import com.thedungeons2077.fluidmotion.lib.Calibration
import com.thedungeons2077.fluidmotion.lib.CostModel
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.Impact
import com.thedungeons2077.fluidmotion.lib.Palette
import com.thedungeons2077.fluidmotion.lib.PowerChoice
import com.thedungeons2077.fluidmotion.lib.PowerMode
import com.thedungeons2077.fluidmotion.lib.PowerState
import com.thedungeons2077.fluidmotion.lib.Quality
import com.thedungeons2077.fluidmotion.lib.Usage
import com.thedungeons2077.fluidmotion.lib.UsageDay
import com.thedungeons2077.fluidmotion.lib.frameRate
import com.thedungeons2077.fluidmotion.lib.profileFps
import com.thedungeons2077.fluidmotion.render.FrameStats
import com.thedungeons2077.fluidmotion.render.RenderLoop
import com.thedungeons2077.fluidmotion.render.UsageStore
import com.thedungeons2077.fluidmotion.scene.SceneKind
import com.thedungeons2077.fluidmotion.settings.Calibrated
import com.thedungeons2077.fluidmotion.settings.Settings
import com.thedungeons2077.fluidmotion.wallpaper.MatrixRainService
import com.thedungeons2077.fluidmotion.wallpaper.NeuralNetService
import com.thedungeons2077.fluidmotion.wallpaper.VelocityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** A run in progress (MEASURE IMPACT, AUTO CALIBRATE): what it is, the step, its number of [steps], seconds left, what to do meanwhile. */
data class RunProgress(val title: String, val label: String, val step: Int, val steps: Int, val secondsLeft: Int, val note: String)

/** AUTO CALIBRATE's step: settling time, and the whole step (ms), for the countdown. */
private const val SETTLE_MS = 1500L
private const val STEP_MS = SETTLE_MS + 1000L * Calibration.MEASURE_SECONDS

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

    /** AUTO or MANUAL; MANUAL's quality; AUTO CALIBRATE's result (null until it has run) and what it measured. */
    var mode by mutableStateOf(settings.mode)
        private set
    var manualQuality by mutableStateOf(settings.manualQuality)
        private set
    var calibrated by mutableStateOf(settings.calibrated)
        private set
    var calibrationReport by mutableStateOf(settings.calibrationReport)
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

    /** MEASURE IMPACT or AUTO CALIBRATE in progress (null when neither is running: one at a time). */
    var run by mutableStateOf<RunProgress?>(null)
        private set

    /** The last MEASURE IMPACT report, and a note when a run couldn't start or stopped early. */
    var impactReport by mutableStateOf(settings.impactReport)
        private set
    var runNote by mutableStateOf<String?>(null)
        private set

    private var runJob: Job? = null

    /** INSIGHT: the last seven days (oldest first, today last), whether the estimate is from this phone's measurements, the battery's size (mAh; null if unknown). */
    var usageDays by mutableStateOf<List<UsageDay>>(emptyList())
        internal set
    var costFromThisPhone by mutableStateOf(false)
        internal set
    var capacityMah by mutableStateOf<Float?>(null)
        internal set

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

    fun chooseMode(m: PowerMode) {
        mode = m
        settings.mode = m
        applyLook()
        applyRate()
    }

    fun chooseQuality(q: Quality) {
        manualQuality = q
        settings.manualQuality = q
        applyLook()
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

    /** The power choices in force: AUTO's calibrated profile (BALANCED until calibrated), or MANUAL's (as Settings.effectivePower). */
    val effectivePower: PowerChoice
        get() = if (mode == PowerMode.AUTO) power.copy(profile = calibrated?.profile ?: Calibration.PROFILE) else power

    /** The selected wallpaper's quality in force (as Settings.quality). */
    val quality: Quality
        get() = if (mode == PowerMode.AUTO) calibrated?.qualities?.get(selected) ?: Quality.DEFAULT else manualQuality

    /** The profile in force's rate on this display (before battery saver and heat). */
    val profileFps: Int get() = profileFps(effectivePower.profile, powerState.refreshRates)

    /** The rate the preview draws at: the same rule as the wallpaper's. */
    val fps: Int get() = frameRate(effectivePower, powerState)

    /** What AUTO CALIBRATE measured was another system or screen (an update, a new resolution): worth running again. */
    val calibrationStale: Boolean get() = calibrated.let { it != null && it.device != deviceKey() }

    /** This phone's system build and screen, as AUTO CALIBRATE records them. */
    private fun deviceKey(): String {
        val m = context.resources.displayMetrics
        return "${Build.FINGERPRINT}|${m.widthPixels}x${m.heightPixels}@${m.density}"
    }

    private fun applyLook() = loop.setLook(palette.colors, glow, quality)

    /** Sends the rate to the preview (not while a run sets its own). */
    private fun applyRate() {
        if (runJob == null) loop.setRate(fps, effectivePower.coolWhenHot)
    }

    /**
     * Runs AUTO CALIBRATE in [scope]: each wallpaper drawn at each quality
     * that draws differently on this screen (on the S25 Ultra's FHD+, HIGH
     * and ULTRA are the same), at AUTO's rate, its frames timed; each gets
     * the highest quality within budget (lib/Quality.kt Calibration). About
     * half a minute, on screen, only while the app is in front.
     */
    fun startCalibration(scope: CoroutineScope) {
        if (runJob != null) return
        runNote = null
        panelOpen = false
        val density = context.resources.displayMetrics.density
        // The steps: per wallpaper, the qualities grouped by what they draw here (each group drawn once).
        val plan = SceneKind.entries.flatMap { kind ->
            Quality.entries.groupBy { Calibration.key(it, density, usesLinkGlow = kind == SceneKind.NEURAL) }.values.map { kind to it }
        }
        val rate = profileFps(Calibration.PROFILE, powerState.refreshRates)
        runJob = scope.launch {
            var finished = false
            try {
                loop.setCalibrating(true)
                loop.setRate(rate, cool = false)
                val samples = SceneKind.entries.associateWith { mutableListOf<Calibration.Sample>() }
                plan.forEachIndexed { index, (kind, group) ->
                    // Drawn at the highest of the group (they all draw the same here). The look first, so a new
                    // wallpaper's scene is made at this quality (not made, then rebuilt).
                    val q = group.last()
                    loop.setLook(settings.palette(kind).colors, settings.glow(kind), q)
                    loop.setKind(kind)
                    run = RunProgress(
                        title = "CALIBRATING",
                        label = "${kind.label} · ${group.joinToString("/") { it.label }}",
                        step = index + 1,
                        steps = plan.size,
                        secondsLeft = ((plan.size - index) * STEP_MS / 1000).toInt(),
                        note = "Each wallpaper at each quality, its frames timed: about half a minute.",
                    )
                    // Settle (a new quality rebuilds the scene), then two one-second measurements.
                    delay(SETTLE_MS)
                    var gpu = 0f
                    var gpuN = 0
                    var cpu = 0f
                    var n = 0
                    repeat(Calibration.MEASURE_SECONDS) {
                        delay(1000)
                        stats?.cost?.let { c ->
                            cpu += c.cpuMs
                            n++
                            if (!c.gpuMs.isNaN()) {
                                gpu += c.gpuMs
                                gpuN++
                            }
                        }
                    }
                    val gpuMs = if (gpuN > 0) gpu / gpuN else Float.NaN
                    val cpuMs = if (n > 0) cpu / n else 0f
                    for (each in group) samples.getValue(kind) += Calibration.Sample(each, gpuMs, cpuMs)
                }
                val qualities = SceneKind.entries.associateWith { Calibration.choose(samples.getValue(it)) }
                val chosen = SceneKind.entries.mapNotNull { k -> samples.getValue(k).firstOrNull { it.quality == qualities[k] } }
                val result = Calibrated(qualities, Calibration.profile(chosen), deviceKey(), System.currentTimeMillis())
                settings.calibrated = result
                calibrated = result
                val report = (listOf("AUTO CALIBRATE · ${stamp()} · ${Build.MANUFACTURER} ${Build.MODEL} · $rate fps · budget %.0f ms GPU, %.0f ms CPU".format(Locale.UK, Calibration.GPU_BUDGET_MS, Calibration.CPU_BUDGET_MS)) +
                    SceneKind.entries.map { k ->
                        "${k.label} → ${qualities.getValue(k).label}: " + samples.getValue(k).joinToString(" · ") {
                            "${it.quality.label} GPU ${if (it.gpuMs.isNaN()) "n/a" else "%.2f".format(Locale.UK, it.gpuMs)} CPU %.2f".format(Locale.UK, it.cpuMs)
                        }
                    } + "rate: ${result.profile.label}").joinToString("\n")
                settings.calibrationReport = report
                calibrationReport = report
                finished = true
            } finally {
                loop.setCalibrating(false)
                if (!finished) runNote = "CALIBRATION STOPPED: run it again with the app in front."
                run = null
                runJob = null
                // Back to the wallpaper shown, as it was (with AUTO's new choice if it's in force).
                applyLook()
                loop.setKind(selected)
                applyRate()
                panelOpen = true
            }
        }
    }

    /**
     * Runs MEASURE IMPACT in [scope]: the selected wallpaper full screen at
     * each rate in turn (STILL first), the battery current sampled once a
     * second, the frames' cost from the preview's loop. About three
     * minutes. It needs the phone unplugged (the current is the battery's
     * then), and keeps going only while the app is in front.
     */
    fun startImpact(scope: CoroutineScope) {
        if (runJob != null) return
        val battery = context.getSystemService(BatteryManager::class.java)
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if ((sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0) {
            runNote = "UNPLUG THE PHONE FIRST: the battery's current is only the phone's own when it isn't charging."
            return
        }
        if (Impact.milliamps(battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)) == null) {
            runNote = "THIS PHONE DOESN'T REPORT ITS BATTERY CURRENT: the frame costs in STATUS are what there is."
            return
        }
        runNote = null
        panelOpen = false
        runJob = scope.launch {
            var finished = false
            try {
                val results = mutableListOf<Impact.Result>()
                val samples = FloatArray(Impact.STEP_SECONDS)
                val steps = Impact.steps(powerState.refreshRates)
                steps.forEachIndexed { index, step ->
                    // The raw rate: no easing for heat, so each step is what that rate costs.
                    loop.setRate(step.fps, cool = false)
                    var n = 0
                    var cpu = 0f
                    var gpu = 0f
                    var gpuN = 0
                    var drawn = 0f
                    var costN = 0
                    for (second in 0 until Impact.STEP_SECONDS) {
                        run = RunProgress(
                            title = "MEASURING IMPACT",
                            label = step.label,
                            step = index + 1,
                            steps = steps.size,
                            secondsLeft = Impact.STEP_SECONDS - second,
                            note = "Leave the phone alone and the brightness as it is: about three minutes in all.",
                        )
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
                val report = Impact.report(results, capacity, "${stamp()} · ${selected.label} · ${palette.label} · GLOW ${glow.label} · QUALITY ${quality.label} · ${Build.MANUFACTURER} ${Build.MODEL}")
                impactReport = report
                settings.impactReport = report
                // What each rate adds for this wallpaper, for INSIGHT's estimates (the latest per wallpaper and rate).
                val base = results.first { it.fps == 0 }.mA
                val model = CostModel.decode(settings.impactModel).toMutableMap()
                for (r in results) if (r.fps > 0 && !r.mA.isNaN() && !base.isNaN()) model[Usage.key(selected.id, r.fps)] = r.mA - base
                settings.impactModel = CostModel.encode(model)
                refreshUsage()
                finished = true
            } finally {
                if (!finished) runNote = "MEASURE IMPACT STOPPED: run it again with the app in front and the phone left alone."
                run = null
                runJob = null
                applyRate()
                panelOpen = true
            }
        }
    }

    /** Reads INSIGHT's log again (the app comes to the front; a wallpaper wrote to it). */
    fun refreshUsage() {
        val model = CostModel(CostModel.decode(settings.impactModel))
        costFromThisPhone = model.fromThisPhone
        usageDays = Usage.days(UsageStore.read(context), model, ZoneId.systemDefault(), LocalDate.now())
        val battery = context.getSystemService(BatteryManager::class.java)
        capacityMah = Impact.capacityMah(
            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        )
    }

    /** What a report says about when and with what it was made: the version and the time (so an old one is plain to see). */
    private fun stamp(): String = "fluid_motion ${BuildConfig.VERSION_NAME} · " +
        java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.UK))

    /** Stops MEASURE IMPACT or AUTO CALIBRATE (the app went to the background, or CANCEL). */
    fun cancelRun() {
        runJob?.cancel()
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
            "wallpaper ${selected.label} · ${palette.label} · GLOW ${glow.label} · ${mode.label} · ${effectivePower.profile.label} ($profileFps fps) · QUALITY ${quality.label}" +
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
            calibrationReport.ifEmpty { "AUTO CALIBRATE: not run yet" } + if (calibrationStale) " (measured on another system or screen)" else "",
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
