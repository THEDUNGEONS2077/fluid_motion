/*
 * ui/HomeScreen.kt: the app's one screen, in cyberdeck_terminal's style:
 * the selected wallpaper running full screen, the brand panel top left,
 * and the controls in a panel at the bottom, within thumb reach, with the
 * main action (SET AS WALLPAPER) last and largest. A tap on the wallpaper
 * hides the controls to see it whole.
 *
 * Layer: ui (Compose).
 */
package com.thedungeons2077.fluidmotion.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.contentDescription
import com.thedungeons2077.fluidmotion.lib.CostModel
import com.thedungeons2077.fluidmotion.lib.UsageDay
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.thedungeons2077.fluidmotion.lib.PowerMode
import com.thedungeons2077.fluidmotion.lib.Calibration
import com.thedungeons2077.fluidmotion.lib.CalibrationSummary
import com.thedungeons2077.fluidmotion.lib.Impact
import com.thedungeons2077.fluidmotion.lib.profileFps
import com.thedungeons2077.fluidmotion.lib.Quality
import com.thedungeons2077.fluidmotion.lib.Zoom
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.thedungeons2077.fluidmotion.BuildConfig
import com.thedungeons2077.fluidmotion.R
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.PowerProfile
import com.thedungeons2077.fluidmotion.lib.Thermal
import com.thedungeons2077.fluidmotion.render.RenderLoop
import com.thedungeons2077.fluidmotion.scene.SceneKind
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The screen. */
@Composable
fun HomeScreen(state: HomeState, loop: RenderLoop) {
    val d = state.uiPalette.colors.toDeck()
    val scope = rememberCoroutineScope()
    val measuring = state.run != null
    // The screen stays on while measuring (a run is about four minutes).
    val view = LocalView.current
    DisposableEffect(measuring) {
        view.keepScreenOn = measuring
        onDispose { view.keepScreenOn = false }
    }
    CompositionLocalProvider(LocalDeck provides d, LocalIndication provides remember(d.accent) { WashIndication(d.accent10) }) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PreviewSurface(loop, Modifier.fillMaxSize())
            // The wallpaper itself: a tap shows or hides the controls; a pinch zooms it (not while measuring).
            // The pinch is the inner modifier, so it sees the fingers first: a pinch is never also a tap.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        enabled = !measuring,
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = if (state.panelOpen) "hide the controls" else "show the controls",
                    ) { state.panelOpen = !state.panelOpen }
                    .pointerInput(measuring) { if (!measuring) pinchToZoom(state) },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(DeckSize.lg),
            ) {
                Brand(state.started)
                state.run?.let { Running(it, state::cancelRun) }
                Spacer(Modifier.weight(1f))
                if (state.panelOpen) {
                    Controls(
                        state,
                        onMeasure = { state.startImpact(scope) },
                        onCalibrate = { state.startCalibration(scope) },
                        modifier = Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.62f).dp),
                    )
                    Spacer(Modifier.height(DeckSize.sm))
                }
                if (!measuring) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DeckSize.sm)) {
                        DeckButton(if (state.panelOpen) "▾ HIDE" else "▴ CONTROLS", { state.panelOpen = !state.panelOpen })
                        DeckButton("SET AS WALLPAPER ↗", state::setAsWallpaper, Modifier.weight(1f), primary = true, label = "set ${state.selected.label} as the wallpaper")
                    }
                }
            }
        }
    }
}

/** The brand panel: `<fluid_motion>`, the time on the left, the date on the right (as the dashboard's). */
@Composable
private fun Brand(ticking: Boolean) {
    val d = LocalDeck.current
    val now = rememberNow(ticking)
    val time = remember { DateTimeFormatter.ofPattern("HH:mm:ss", Locale.UK) }
    val date = remember { DateTimeFormatter.ofPattern("EEE dd MMM yyyy", Locale.UK) }
    val at = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    Masthead(Modifier.width(IntrinsicSize.Max)) {
        Row {
            DeckText("<", DeckType.brand, d.textDim)
            DeckText("fluid_motion", DeckType.brand, d.accent, glow = d.glowText)
            DeckText(">", DeckType.brand, d.textDim)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DeckText(time.format(at), DeckType.small, d.textBright)
            Spacer(Modifier.width(DeckSize.md))
            DeckText(date.format(at).uppercase(Locale.UK), DeckType.small, d.textDim)
        }
    }
}

/** The time now, once a second while [ticking] (the app in front): no wake-ups in the background. */
@Composable
private fun rememberNow(ticking: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ticking) {
        while (ticking) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    return now
}

/**
 * MEASURE IMPACT or AUTO CALIBRATE under way: which step, how long left,
 * and how to stop. For MEASURE IMPACT it changes once a second in every
 * step alike, so it doesn't skew the comparison between them.
 */
@Composable
private fun Running(p: RunProgress, onCancel: () -> Unit) {
    val d = LocalDeck.current
    val shape = RoundedCornerShape(DeckSize.radius)
    Column(
        Modifier
            .padding(top = DeckSize.md)
            .fillMaxWidth()
            .background(d.surfaceRaised, shape)
            .border(DeckSize.hairline, d.accent35, shape)
            .padding(DeckSize.md),
        verticalArrangement = Arrangement.spacedBy(DeckSize.xs),
    ) {
        DeckText("${p.title} · ${p.label} (${p.step}/${p.steps}) · ${p.secondsLeft} S", DeckType.section, d.textBright, glow = d.glowText)
        DeckText(p.note, DeckType.detail, d.textDim)
        DeckButton("CANCEL", onCancel, Modifier.fillMaxWidth())
    }
}

/** The controls panel: what to show, its colours and glow, power and quality, the app's colours, the status and its tools. */
@Composable
private fun Controls(state: HomeState, onMeasure: () -> Unit, onCalibrate: () -> Unit, modifier: Modifier) {
    val d = LocalDeck.current
    val shape = RoundedCornerShape(DeckSize.radius)
    Column(
        modifier
            .fillMaxWidth()
            .background(d.surfaceRaised, shape)
            .border(DeckSize.hairline, d.accent35, shape)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DeckSize.lg, vertical = DeckSize.sm),
    ) {
        // A note from a run or a choice (MEASURE IMPACT, AUTO CALIBRATE, USE): above the sections, so a closed one doesn't hide it.
        state.runNote?.let { DeckText(it, DeckType.detail, d.textBright, Modifier.padding(vertical = DeckSize.sm)) }
        val fold = Folds(state)

        fold("WALLPAPER", state.selected.label) {
            Tiles(
                items = SceneKind.entries,
                selected = state.selected,
                active = state.active,
                label = { it.label },
                picture = { thumbnail(it) },
                onSelect = state::select,
            )
        }

        fold("COLOURS", state.palette.label) {
            DeckText("For ${state.selected.label}: each wallpaper keeps its own.", DeckType.detail, d.textDim, Modifier.padding(bottom = DeckSize.sm))
            Swatches(Palettes.ALL, state.palette.id, state::choosePalette, "wallpaper colours")
        }

        fold("GLOW", state.glow.label) {
            Segmented(GlowLevel.entries.map { it.label }, state.glow.ordinal, { state.chooseGlow(GlowLevel.entries[it]) })
        }

        fold("ZOOM", Zoom.label(state.zoom)) {
            Stepper(
                value = Zoom.label(state.zoom),
                what = "zoom",
                canDown = state.zoom > Zoom.MIN,
                canUp = state.zoom < Zoom.MAX,
                canReset = state.zoom != Zoom.DEFAULT,
                onDown = { state.stepZoom(-1) },
                onUp = { state.stepZoom(1) },
                onReset = { state.chooseZoom(Zoom.DEFAULT) },
            )
            DeckText("Or pinch the wallpaper. Each wallpaper keeps its own zoom, as sharp at any.", DeckType.detail, d.text, Modifier.padding(top = DeckSize.sm))
        }

        fold("POWER", "${state.mode.label} · ${state.effectivePower.profile.label} · ${state.quality.label}") {
            Segmented(PowerMode.entries.map { it.label }, state.mode.ordinal, { state.chooseMode(PowerMode.entries[it]) })
            if (state.mode == PowerMode.AUTO) {
                DeckText(autoText(state), DeckType.detail, d.text, Modifier.padding(top = DeckSize.sm, bottom = DeckSize.sm))
                DeckButton(
                    if (state.calibrated == null) "AUTO CALIBRATE" else "CALIBRATE AGAIN",
                    onCalibrate,
                    Modifier.fillMaxWidth(),
                    primary = state.calibrated == null || state.calibrationStale,
                    label = "time each wallpaper at each quality on this phone, about half a minute",
                )
            } else {
                Spacer(Modifier.height(DeckSize.sm))
                Segmented(PowerProfile.entries.map { it.label }, state.power.profile.ordinal, { state.choosePower(state.power.copy(profile = PowerProfile.entries[it])) })
                DeckText(
                    "${state.profileFps} frames a second on this display while the wallpaper is on screen; nothing at all while it's hidden.",
                    DeckType.detail,
                    d.textDim,
                    Modifier.padding(top = DeckSize.sm, bottom = DeckSize.sm),
                )
                DeckText("QUALITY", DeckType.small, d.textDim, Modifier.padding(bottom = DeckSize.xs).semantics { heading() })
                Segmented(Quality.entries.map { it.label }, state.manualQuality.ordinal, { state.chooseQuality(Quality.entries[it]) })
                DeckText(qualityText(state.manualQuality), DeckType.detail, d.textDim, Modifier.padding(top = DeckSize.sm))
            }
            Spacer(Modifier.height(DeckSize.xs))
            Toggle("STILL IN BATTERY SAVER", "A still frame while battery saver is on.", state.power.stillInSaver, { state.choosePower(state.power.copy(stillInSaver = it)) })
            Toggle("COOL DOWN WHEN HOT", "SMOOTH eases to 30 as the phone nears its heat limit; half the frames when warm, a still frame when hot.", state.power.coolWhenHot, { state.choosePower(state.power.copy(coolWhenHot = it)) })
        }

        // AUTO CALIBRATE's summary and three ways to set MANUAL; it opens by itself when a calibration finishes.
        fold(CALIBRATION_SECTION, state.calibrationSummary?.let { "RECOMMENDED ${it.recommended.profile.label} · ${it.recommended.quality.label}" } ?: "NOT RUN") {
            CalibrationResult(state, onCalibrate)
        }

        fold("INSIGHT", "") {
            Insight(state)
            // Everything behind INSIGHT and the measurements, as text to paste back while tuning the wallpapers.
            DeckButton("COPY BATTERY DATA", state::copyBatteryData, Modifier.fillMaxWidth(), label = "copy the week's battery data, every measurement and the settings, to paste")
            state.batteryNote?.let { DeckText(it, DeckType.detail, d.textBright, Modifier.padding(top = DeckSize.sm)) }
        }

        fold("APP COLOURS", state.uiPalette.label) {
            Swatches(Palettes.ALL, state.uiPalette.id, state::chooseUiPalette, "app colours")
        }

        fold("STATUS", "${BuildConfig.VERSION_NAME}") {
            DeckText(status(state), DeckType.detail, d.text)
            DeckText("fluid_motion ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", DeckType.detail, d.textDim)
            // Stacked, full width: side by side, their names don't fit a phone.
            Column(Modifier.fillMaxWidth().padding(vertical = DeckSize.sm), verticalArrangement = Arrangement.spacedBy(DeckSize.sm)) {
                DeckButton("MEASURE IMPACT", onMeasure, Modifier.fillMaxWidth(), label = "measure the battery cost of each rate, about four minutes")
                DeckButton("COPY DIAGNOSTICS", state::copyDiagnostics, Modifier.fillMaxWidth(), label = "copy this phone's numbers to paste")
            }
            DeckText(
                state.impactReport.ifEmpty { "MEASURE IMPACT shows what each rate costs the battery on this phone: unplug it first." },
                DeckType.detail,
                d.textDim,
                Modifier.padding(bottom = DeckSize.sm),
            )
        }
    }
}

/** The settings' sections, each folding (Section), open or closed as HomeState.openSections says: `fold(title, value) { … }`. */
private class Folds(private val state: HomeState) {
    @Composable
    operator fun invoke(title: String, value: String, content: @Composable ColumnScope.() -> Unit) =
        Section(title, value, title in state.openSections, { state.toggleSection(title) }, content)
}

/**
 * AUTO CALIBRATE's summary (lib/CalibrationSummary.kt): what AUTO picked
 * for each wallpaper and what its frames cost, then three ways to set
 * MANUAL, each with its battery, GPU and budget figures and a USE button;
 * the recommended one lit.
 */
@Composable
private fun CalibrationResult(state: HomeState, onCalibrate: () -> Unit) {
    val d = LocalDeck.current
    val summary = state.calibrationSummary
    if (summary == null) {
        DeckText(
            if (state.calibrated == null) "Run AUTO CALIBRATE (POWER ▸ AUTO) to time each wallpaper on this phone: this shows what it found, and the settings it recommends."
            else "Calibrate again to see the summary: the last calibration was made before 0.11.0 kept its measurements.",
            DeckType.detail,
            d.text,
            Modifier.padding(bottom = DeckSize.sm),
        )
        DeckButton(if (state.calibrated == null) "AUTO CALIBRATE" else "CALIBRATE AGAIN", onCalibrate, Modifier.fillMaxWidth(), label = "time each wallpaper at each quality on this phone, about half a minute")
        return
    }
    val capacity = state.capacityMah
    DeckText("AUTO · EACH WALLPAPER", DeckType.small, d.textDim, Modifier.padding(bottom = DeckSize.xs).semantics { heading() })
    for (p in summary.picks) {
        val cost = p.sample?.let { s -> (if (s.gpuMs.isNaN()) "" else "GPU %.1f".format(Locale.UK, s.gpuMs)) + " · CPU %.1f ms".format(Locale.UK, s.cpuMs) } ?: ""
        val over = if (p.sample?.within == false) " · OVER BUDGET" else ""
        DeckText("${p.label}: ${p.quality.label} · ${p.profile.label}  ($cost$over)", DeckType.detail, if (p.sample?.within == false) d.textBright else d.text)
    }
    if (summary.heavy.isNotEmpty()) {
        DeckText(
            "${CalibrationSummary.names(summary.heavy)} ${if (summary.heavy.size == 1) "is" else "are"} over the ${Calibration.GPU_BUDGET_MS.roundToInt()} ms budget even at LOW here, so AUTO draws ${if (summary.heavy.size == 1) "it" else "them"} at ECO. MANUAL draws at what you choose, and still smoothly if the phone keeps up.",
            DeckType.detail,
            d.textDim,
            Modifier.padding(top = DeckSize.xs),
        )
    }
    DeckText("AUTO stays the closest fit: it picks for each wallpaper. MANUAL sets one rate and one quality for all; three ways to set it:", DeckType.detail, d.text, Modifier.padding(vertical = DeckSize.sm))
    for (p in summary.presets) {
        val lit = p == summary.recommended
        val shape = RoundedCornerShape(DeckSize.radius)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = DeckSize.sm)
                .background(d.bg, shape)
                .border(DeckSize.hairline, if (lit) d.accent35 else d.line, shape)
                .padding(DeckSize.md),
        ) {
            DeckText("${p.name} · ${p.profile.label} ${p.fps} fps · ${p.quality.label}", DeckType.control, if (lit) d.textBright else d.text, glow = if (lit) d.glowText else null)
            DeckText(p.about, DeckType.detail, d.textDim, Modifier.padding(bottom = DeckSize.xs))
            DeckText(presetFigures(p, capacity, state.costFromThisPhone), DeckType.detail, d.text)
            Spacer(Modifier.height(DeckSize.sm))
            DeckButton("USE ${p.name}", { state.usePreset(p) }, Modifier.fillMaxWidth(), primary = lit, label = "switch to MANUAL at ${p.profile.label} and QUALITY ${p.quality.label}")
        }
    }
}

/** A preset's figures in words: battery an hour on screen, the GPU's busiest, and the frame budget. */
private fun presetFigures(p: CalibrationSummary.Preset, capacity: Float?, measured: Boolean): String {
    val battery = if (capacity != null) {
        "Battery: about %.1f%% an hour on screen (up to %.1f%%)".format(Locale.UK, Impact.percentPerHour(p.meanMa, capacity), Impact.percentPerHour(p.maxMa, capacity))
    } else {
        "Battery: about +%d mA on screen (up to +%d)".format(Locale.UK, p.meanMa.roundToInt(), p.maxMa.roundToInt())
    }
    val source = if (measured) "" else ", from a typical figure: MEASURE IMPACT gives this phone's own"
    val busy = if (p.gpuBusy.isNaN()) "" else "\nGPU: busy %d%% of the time for the heaviest wallpaper".format(Locale.UK, (p.gpuBusy * 100).roundToInt().coerceAtLeast(1))
    val budget = if (p.over.isEmpty()) "\nEvery wallpaper within the frame budget." else "\nOver the frame budget: ${CalibrationSummary.names(p.over)} (${p.total - p.over.size} of ${p.total} within)."
    return "$battery$source.$busy$budget"
}

/**
 * INSIGHT: the wallpapers' week on this phone: today in words, then seven
 * days as bars of the battery they used (estimated), with how it was
 * estimated. Read from the log the wallpapers keep (lib/Usage.kt).
 */
@Composable
private fun Insight(state: HomeState) {
    val d = LocalDeck.current
    val days = state.usageDays
    val today = days.lastOrNull()
    if (today == null || days.all { it.onScreenMs == 0L }) {
        DeckText(
            "Nothing yet: once a fluid_motion wallpaper is on your home or lock screen, its time on screen and what it costs show here, day by day.",
            DeckType.detail,
            d.textDim,
            Modifier.padding(bottom = DeckSize.sm),
        )
        return
    }
    val capacity = state.capacityMah
    DeckText(todayText(today, capacity), DeckType.detail, d.text)
    UsageBars(days, capacity, Modifier.padding(vertical = DeckSize.md))
    DeckText(
        (if (state.costFromThisPhone) "Estimated from what MEASURE IMPACT found on this phone (the extra current at each rate)."
        else "Estimated from a typical figure (+${CostModel.TYPICAL_MA_PER_FPS} mA per frame a second): run MEASURE IMPACT for this phone's own.") +
            " The system's battery screen counts the screen's own power as the screen's, so it shows less for fluid_motion than this.",
        DeckType.detail,
        d.textDim,
        Modifier.padding(bottom = DeckSize.sm),
    )
}

/** Today in a few lines: time on screen, the battery it used, its frames, where the time went. */
private fun todayText(t: UsageDay, capacity: Float?): String {
    val lines = mutableListOf("TODAY · ON SCREEN ${duration(t.onScreenMs)} (drawing ${duration(t.drawingMs)}, still ${duration(t.stillMs)})")
    val share = capacity?.let { " · %.1f%% of the battery".format(Locale.UK, t.mAh / it * 100) } ?: ""
    val hourly = if (capacity != null && t.drawingMs > 60_000) " · %.1f%% an hour on screen".format(Locale.UK, t.mAh / capacity * 100 / (t.onScreenMs / 3_600_000f)) else ""
    lines += "≈ %.0f mAh".format(Locale.UK, t.mAh) + share + hourly
    if (t.frames > 0) {
        val gpu = if (t.gpuMs.isNaN()) "" else " · GPU %.2f ms".format(Locale.UK, t.gpuMs)
        lines += "${frameCount(t.frames)} frames · CPU %.2f ms$gpu a frame".format(Locale.UK, t.cpuMs)
    }
    lines += t.breakdown.take(3).joinToString(" · ") { (k, ms) -> "${label(k)} ${duration(ms)}" }
    return lines.joinToString("\n")
}

/** A log key ("matrix-rain@30") as the dashboard names it: "MATRIX RAIN 30 FPS", "… STILL". */
private fun label(key: String): String {
    val kind = SceneKind.find(key.substringBefore('@')).label
    val fps = key.substringAfter('@').toIntOrNull() ?: 0
    return if (fps == 0) "$kind STILL" else "$kind $fps FPS"
}

/** A duration as hours and minutes: "2 H 14 M", "14 M", "< 1 M". */
private fun duration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "< 1 M"
        minutes < 60 -> "$minutes M"
        else -> "${minutes / 60} H ${minutes % 60} M"
    }
}

/** A count, short: "862", "54 K", "1.2 M". */
private fun frameCount(n: Long): String = when {
    n < 10_000 -> "$n"
    n < 1_000_000 -> "${n / 1000} K"
    else -> "%.1f M".format(Locale.UK, n / 1e6)
}

/**
 * Seven days as bars: each the battery that day's wallpaper use took
 * (% of the battery, or mAh when the battery's size isn't known), today
 * lit. Each day reads out its figures to TalkBack.
 */
@Composable
private fun UsageBars(days: List<UsageDay>, capacity: Float?, modifier: Modifier = Modifier) {
    val d = LocalDeck.current
    val values = days.map { if (capacity != null) it.mAh / capacity * 100 else it.mAh }
    val top = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    val dayName = remember { DateTimeFormatter.ofPattern("EEE", Locale.UK) }
    Row(modifier.fillMaxWidth().height(116.dp), horizontalArrangement = Arrangement.spacedBy(DeckSize.xs), verticalAlignment = Alignment.Bottom) {
        days.forEachIndexed { i, day ->
            val v = values[i]
            val lit = i == days.lastIndex
            val text = when {
                day.onScreenMs == 0L -> "–"
                capacity != null -> "%.1f%%".format(Locale.UK, v)
                else -> "%.0f".format(Locale.UK, v)
            }
            val name = day.date.format(dayName).uppercase(Locale.UK)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$name: ${duration(day.onScreenMs)} on screen, about ${"%.0f".format(Locale.UK, day.mAh)} milliamp hours"
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                DeckText(text, DeckType.small, if (lit) d.textBright else d.textDim, maxLines = 1)
                Spacer(Modifier.height(DeckSize.xs))
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height((v / top * 64f).coerceAtLeast(if (day.onScreenMs > 0) 2f else 0f).dp)
                        .background(if (lit) d.accent else d.accent35),
                )
                Spacer(Modifier.height(DeckSize.xs))
                DeckText(name, DeckType.small, if (lit) d.textBright else d.textDim, glow = if (lit) d.glowText else null, maxLines = 1)
            }
        }
    }
}

/** AUTO's choice, or what it will do before it's been calibrated. */
private fun autoText(state: HomeState): String {
    val c = state.calibrated
        ?: return "AUTO finds the balance for this phone: 30 frames a second (where the screen can slow down too) and the sharpest " +
            "quality each wallpaper draws cheaply here. Not calibrated yet: until then BALANCED, QUALITY HIGH."
    // Each wallpaper's quality, and its rate where it isn't AUTO's usual BALANCED.
    val each = SceneKind.entries.joinToString(" · ") { k ->
        val r = c.profiles[k]?.takeIf { it != Calibration.PROFILE }?.let { " ${it.label}" } ?: ""
        "${k.label} ${c.qualities[k]?.label ?: Quality.DEFAULT.label}$r"
    }
    val stale = if (state.calibrationStale) "\nThe system or screen has changed since: calibrate again." else ""
    return "Calibrated for this phone: ${Calibration.PROFILE.label} (${profileFps(Calibration.PROFILE, state.powerState.refreshRates)} fps) unless marked · QUALITY $each$stale\nCALIBRATION has the summary and recommended settings."
}

/** What a quality means on this phone. */
@Composable
private fun qualityText(q: Quality): String {
    val density = LocalDensity.current.density
    val share = (q.scale(density) * 100).roundToInt()
    val res = if (share >= 100) "full resolution" else "$share% of the screen's resolution, scaled up smoothly"
    val glow = if (q.linkGlow) "" else "; NEURAL NET without its links' soft glow"
    return "${q.label}: drawn at $res$glow. Lower costs the GPU less on weaker phones."
}

/** What the preview is doing and why, and what its frames cost (measured), in a line or two. */
private fun status(state: HomeState): String {
    val s = state.stats
    val p = state.powerState
    val fps = s?.fps ?: state.fps
    if (s?.failure != null) return "CAN'T DRAW HERE: ${s.failure}"
    val why = when {
        fps == 0 && p.powerSave && state.power.stillInSaver -> "STILL · BATTERY SAVER"
        fps == 0 -> "STILL · PHONE HOT"
        s?.eased == true -> "$fps FPS · EASED, PHONE WARMING"
        p.thermal >= Thermal.MODERATE && state.power.coolWhenHot -> "$fps FPS · HALF RATE, PHONE WARM"
        else -> "$fps FPS"
    }
    val c = s?.cost
    val cost = if (c != null && fps > 0 && c.fps > 0f) {
        val gpu = if (c.gpuMs.isNaN()) "" else " · GPU %.2f MS".format(Locale.UK, c.gpuMs)
        "\nA FRAME: CPU %.2f MS$gpu · %.0f%% LATE".format(Locale.UK, c.cpuMs, c.late * 100)
    } else {
        ""
    }
    val low = if (s?.lowPriority == true) " · GPU LOW PRIORITY" else ""
    return why + low + cost
}

/** A pinch before a zoom starts to follow it: the fingers' spread must change by this share (so two fingers set down don't nudge it). */
private const val PINCH_SLOP = 0.06f

/**
 * A pinch on the preview zooms the wallpaper: its zoom times how far the
 * fingers spread, a step at a time as they move (HomeState.previewZoom),
 * kept when they lift. One finger is left alone: a tap still shows or hides
 * the controls.
 */
private suspend fun PointerInputScope.pinchToZoom(state: HomeState) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val start = state.zoom
        var spread = 1f
        var pinching = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                spread *= event.calculateZoom()
                if (!pinching && abs(spread - 1f) > PINCH_SLOP) pinching = true
                if (pinching) {
                    state.previewZoom(start * spread)
                    // Taken: the tap underneath sees moved fingers and doesn't fire.
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
        if (pinching) state.keepZoom()
    }
}

/** A wallpaper's picture. */
private fun thumbnail(kind: SceneKind): Int = when (kind) {
    SceneKind.RAIN -> R.drawable.thumb_rain
    SceneKind.NEURAL -> R.drawable.thumb_neural
    SceneKind.VELOCITY -> R.drawable.thumb_velocity
    SceneKind.DATA_FLOW -> R.drawable.thumb_flow
    SceneKind.MEGACITY -> R.drawable.thumb_megacity
    SceneKind.HORIZON -> R.drawable.thumb_horizon
    SceneKind.SUNSET -> R.drawable.thumb_sunset
    SceneKind.PRISM -> R.drawable.thumb_prism
}
