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
    val measuring = state.impact != null
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
                state.impact?.let { Measuring(it, state::cancelImpact) }
                Spacer(Modifier.weight(1f))
                if (state.panelOpen) {
                    Controls(state, { state.startImpact(scope) }, Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.62f).dp))
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
 * MEASURE IMPACT under way: which step, how long left, and how to stop.
 * It changes once a second in every step alike, so it doesn't skew the
 * comparison between them.
 */
@Composable
private fun Measuring(p: ImpactProgress, onCancel: () -> Unit) {
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
        DeckText("MEASURING IMPACT · ${p.label} (${p.step}/${p.steps}) · ${p.secondsLeft} S", DeckType.section, d.textBright, glow = d.glowText)
        DeckText("Leave the phone alone and the brightness as it is: about three minutes in all.", DeckType.detail, d.textDim)
        DeckButton("CANCEL", onCancel, Modifier.fillMaxWidth())
    }
}

/** The controls panel: what to show, its colours and glow, power, the app's colours, the status and its tools. */
@Composable
private fun Controls(state: HomeState, onMeasure: () -> Unit, modifier: Modifier) {
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
        Segmented(PowerProfile.entries.map { it.label }, state.power.profile.ordinal, { state.choosePower(state.power.copy(profile = PowerProfile.entries[it])) })
        DeckText(
            "${state.profileFps} frames a second on this display while the wallpaper is on screen; nothing at all while it's hidden.",
            DeckType.detail,
            d.textDim,
            Modifier.padding(top = DeckSize.sm, bottom = DeckSize.xs),
        )
        Toggle("STILL IN BATTERY SAVER", "A still frame while battery saver is on.", state.power.stillInSaver, { state.choosePower(state.power.copy(stillInSaver = it)) })
        Toggle("COOL DOWN WHEN HOT", "SMOOTH eases to 30 as the phone nears its heat limit; half the frames when warm, a still frame when hot.", state.power.coolWhenHot, { state.choosePower(state.power.copy(coolWhenHot = it)) })

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
        state.impactNote?.let { DeckText(it, DeckType.detail, d.textBright, Modifier.padding(bottom = DeckSize.sm)) }
        DeckText(
            state.impactReport.ifEmpty { "MEASURE IMPACT shows what each rate costs the battery on this phone: unplug it first." },
            DeckType.detail,
            d.textDim,
            Modifier.padding(bottom = DeckSize.sm),
        )
    }
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
}
