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
import com.thedungeons2077.fluidmotion.lib.Quality
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
    // The screen stays on while measuring (a run is about three minutes).
    val view = LocalView.current
    DisposableEffect(measuring) {
        view.keepScreenOn = measuring
        onDispose { view.keepScreenOn = false }
    }
    CompositionLocalProvider(LocalDeck provides d, LocalIndication provides remember(d.accent) { WashIndication(d.accent10) }) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PreviewSurface(loop, Modifier.fillMaxSize())
            // The wallpaper itself: a tap shows or hides the controls.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        enabled = !measuring,
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = if (state.panelOpen) "hide the controls" else "show the controls",
                    ) { state.panelOpen = !state.panelOpen },
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
        SectionTitle("WALLPAPER")
        Tiles(
            items = SceneKind.entries,
            selected = state.selected,
            active = state.active,
            label = { it.label },
            picture = { thumbnail(it) },
            onSelect = state::select,
        )

        SectionTitle("COLOURS · ${state.selected.label}")
        Swatches(Palettes.ALL, state.palette.id, state::choosePalette, "wallpaper colours")

        SectionTitle("GLOW")
        Segmented(GlowLevel.entries.map { it.label }, state.glow.ordinal, { state.chooseGlow(GlowLevel.entries[it]) })

        SectionTitle("POWER")
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

        SectionTitle("INSIGHT")
        Insight(state)

        SectionTitle("APP COLOURS")
        Swatches(Palettes.ALL, state.uiPalette.id, state::chooseUiPalette, "app colours")

        SectionTitle("STATUS")
        DeckText(status(state), DeckType.detail, d.text)
        DeckText("fluid_motion ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", DeckType.detail, d.textDim)
        // Stacked, full width: side by side, their names don't fit a phone.
        Column(Modifier.fillMaxWidth().padding(vertical = DeckSize.sm), verticalArrangement = Arrangement.spacedBy(DeckSize.sm)) {
            DeckButton("MEASURE IMPACT", onMeasure, Modifier.fillMaxWidth(), label = "measure the battery cost of each rate, about three minutes")
            DeckButton("COPY DIAGNOSTICS", state::copyDiagnostics, Modifier.fillMaxWidth(), label = "copy this phone's numbers to paste")
        }
        state.runNote?.let { DeckText(it, DeckType.detail, d.textBright, Modifier.padding(bottom = DeckSize.sm)) }
        DeckText(
            state.impactReport.ifEmpty { "MEASURE IMPACT shows what each rate costs the battery on this phone: unplug it first." },
            DeckType.detail,
            d.textDim,
            Modifier.padding(bottom = DeckSize.sm),
        )
    }
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
    val each = SceneKind.entries.joinToString(" · ") { "${it.label} ${c.qualities[it]?.label ?: Quality.DEFAULT.label}" }
    val stale = if (state.calibrationStale) "\nThe system or screen has changed since: calibrate again." else ""
    return "Calibrated for this phone: ${c.profile.label} (${state.profileFps} fps) · QUALITY $each$stale"
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

/** A wallpaper's picture. */
private fun thumbnail(kind: SceneKind): Int = when (kind) {
    SceneKind.RAIN -> R.drawable.thumb_rain
    SceneKind.NEURAL -> R.drawable.thumb_neural
    SceneKind.VELOCITY -> R.drawable.thumb_velocity
    SceneKind.DATA_FLOW -> R.drawable.thumb_flow
}
