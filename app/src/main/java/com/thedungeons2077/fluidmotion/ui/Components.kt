/*
 * ui/Components.kt: cyberdeck_terminal's pieces in Compose: the masthead
 * (scanlines, HUD corner brackets), section titles with their fading lit
 * rule, segmented choices, palette swatches, wallpaper tiles, toggles,
 * buttons, and the press wash every control shares.
 *
 * Layer: ui (Compose foundation only: no Material, so nothing here looks
 * like anything but the dashboard). Every control is a real radio button,
 * switch or button to accessibility services (semantics), and at least
 * 48 dp tall. Nothing animates in a loop: an idle screen draws nothing.
 */
package com.thedungeons2077.fluidmotion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thedungeons2077.fluidmotion.lib.Palette
import kotlinx.coroutines.launch

/** Text in the deck's family, optionally glowing; [minLines] keeps room for that many lines (so neighbours line up). */
@Composable
fun DeckText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    glow: Shadow? = null,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, shadow = glow, textAlign = align),
        maxLines = maxLines,
        minLines = minLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The press wash (theme.css: a press washes --accent-10): what every
 * clickable here shows while held. A Compose Indication, so all controls
 * share it.
 */
class WashIndication(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = WashNode(interactionSource, color)
    override fun equals(other: Any?) = other is WashIndication && other.color == color
    override fun hashCode() = color.hashCode()
}

private class WashNode(private val source: InteractionSource, private val color: Color) : Modifier.Node(), DrawModifierNode {
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect {
                val was = pressed
                pressed = when (it) {
                    is PressInteraction.Press -> true
                    is PressInteraction.Release, is PressInteraction.Cancel -> false
                    else -> pressed
                }
                if (was != pressed) invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) drawRect(color)
    }
}

/**
 * A lit panel (the desktop's .masthead): the surface with a phosphor glow
 * from the top left, faint scanlines, a hairline frame and two glowing
 * corner brackets.
 */
@Composable
fun Masthead(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val d = LocalDeck.current
    Column(
        modifier = modifier
            .drawBehind {
                drawRect(d.surface)
                drawRect(Brush.radialGradient(listOf(d.accent10, Color.Transparent), center = Offset.Zero, radius = size.maxDimension * 0.8f))
                // Scanlines: a line every 3 dp.
                val pitch = 3.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawRect(d.accent05, topLeft = Offset(0f, y), size = Size(size.width, 1.dp.toPx()))
                    y += pitch
                }
                drawRect(d.line, style = Stroke(1.dp.toPx()))
                // HUD corner brackets, top left and bottom right, with a soft glow under them.
                val arm = 9.dp.toPx()
                val w = 1.dp.toPx()
                for ((glow, width) in listOf(d.accent22 to 4 * w, d.accent to w)) {
                    drawRect(glow, Offset(0f, 0f), Size(arm, width))
                    drawRect(glow, Offset(0f, 0f), Size(width, arm))
                    drawRect(glow, Offset(size.width - arm, size.height - width), Size(arm, width))
                    drawRect(glow, Offset(size.width - width, size.height - arm), Size(width, arm))
                }
            }
            .padding(DeckSize.md),
        verticalArrangement = Arrangement.spacedBy(DeckSize.xs),
        content = content,
    )
}

/** A section's heading, with a lit rule running on from it and fading out. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val d = LocalDeck.current
    Row(modifier.fillMaxWidth().padding(top = DeckSize.md, bottom = DeckSize.sm), verticalAlignment = Alignment.CenterVertically) {
        DeckText(text, DeckType.section, d.textBright, Modifier.semantics { heading() })
        Spacer(Modifier.width(DeckSize.sm))
        Box(Modifier.weight(1f).height(DeckSize.hairline).background(Brush.horizontalGradient(listOf(d.accent35, Color.Transparent))))
    }
}

/** A row of choices with one lit (the dashboard's .segmented): radio buttons to accessibility services. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val d = LocalDeck.current
    val shape = RoundedCornerShape(DeckSize.radius)
    Row(modifier.fillMaxWidth().height(DeckSize.control).border(DeckSize.hairline, d.line, shape).selectableGroup()) {
        options.forEachIndexed { i, label ->
            if (i > 0) Box(Modifier.width(DeckSize.hairline).fillMaxHeight().background(d.line))
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (on) d.wash else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) {
                DeckText(label, DeckType.control, if (on) d.textBright else d.textDim, glow = if (on) d.glowText else null, maxLines = 1)
            }
        }
    }
}

/**
 * Palette swatches (the dashboard's): each chip previews its palette in
 * that palette's own colours, so the choice is seen, not read. Three a row.
 */
@Composable
fun Swatches(palettes: List<Palette>, selected: String, onSelect: (Palette) -> Unit, what: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(DeckSize.sm)) {
        palettes.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DeckSize.sm)) {
                row.forEach { p -> Swatch(p, p.id == selected, { onSelect(p) }, what, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Swatch(p: Palette, on: Boolean, onClick: () -> Unit, what: String, modifier: Modifier) {
    val c = p.colors.toDeck()
    val shape = RoundedCornerShape(DeckSize.radius)
    Column(
        modifier
            .heightIn(min = DeckSize.control)
            .drawBehind {
                // The lit chip's glow: a few soft frames in its accent.
                if (on) for (k in 1..3) drawRoundRect(c.accent.copy(alpha = 0.16f / k), Offset(-k * 2f, -k * 2f), Size(size.width + k * 4f, size.height + k * 4f), CornerRadius(4f), style = Stroke(2f))
            }
            .background(c.surface, shape)
            .border(DeckSize.hairline, if (on) c.accent else c.line, shape)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "${p.label} $what" }
            .padding(DeckSize.sm),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(Brush.horizontalGradient(listOf(c.cool, c.accent)), shape))
        DeckText((if (on) "● " else "") + p.label, DeckType.small, c.text, maxLines = 1)
    }
}

/**
 * The wallpapers as picture tiles in a row: the lit one is the one shown
 * and set; [active] marks the one on the home screen. Names may wrap to a
 * second line, and every name keeps room for two, so the tiles line up.
 * Up to four share the width; more and the row scrolls sideways, each tile
 * wide enough for its name and "● ON HOME" (a tile cut off at the edge says
 * there's more), opening scrolled to the chosen one.
 */
@Composable
fun <T> Tiles(items: List<T>, selected: T, active: T?, label: (T) -> String, picture: (T) -> Int, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val d = LocalDeck.current
    val shape = RoundedCornerShape(DeckSize.radius)
    val scrolls = items.size > TILES_THAT_FIT
    val scroll = rememberScrollState()
    if (scrolls) {
        // Open with the chosen tile in sight (once: the row then scrolls as the hand moves it).
        val step = with(LocalDensity.current) { (TILE_WIDTH + DeckSize.sm).toPx() }
        LaunchedEffect(Unit) { scroll.scrollTo((items.indexOf(selected) * step - step / 2).toInt().coerceAtLeast(0)) }
    }
    Row(
        modifier.fillMaxWidth().then(if (scrolls) Modifier.horizontalScroll(scroll) else Modifier).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(DeckSize.sm),
    ) {
        items.forEach { item ->
            val on = item == selected
            Column(
                (if (scrolls) Modifier.width(TILE_WIDTH) else Modifier.weight(1f))
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(item) }),
                verticalArrangement = Arrangement.spacedBy(DeckSize.xs),
            ) {
                Image(
                    painter = painterResource(picture(item)),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f)
                        .border(DeckSize.hairline, if (on) d.accent else d.line, shape),
                )
                DeckText(label(item), DeckType.small, if (on) d.textBright else d.textDim, glow = if (on) d.glowText else null, maxLines = 2, minLines = 2)
                DeckText(if (item == active) "● ON HOME" else " ", DeckType.small, d.accent, maxLines = 1)
            }
        }
    }
}

/** Picture tiles that share a phone's width; past this many the row scrolls. */
private const val TILES_THAT_FIT = 4

/** A tile's width when the row scrolls: room for "● ON HOME" and a name like "MEGACITY" (12 sp mono, about 8 dp a letter). */
private val TILE_WIDTH = 84.dp

/** A setting that's on or off: a switch to accessibility services, the whole row its target. */
@Composable
fun Toggle(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val d = LocalDeck.current
    val shape = RoundedCornerShape(DeckSize.radius)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = DeckSize.control)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = DeckSize.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DeckSize.md),
    ) {
        Column(Modifier.weight(1f)) {
            DeckText(label, DeckType.control, d.text)
            DeckText(detail, DeckType.detail, d.textDim)
        }
        Box(
            Modifier
                .width(64.dp)
                .height(32.dp)
                .background(if (checked) d.wash else Color.Transparent, shape)
                .border(DeckSize.hairline, if (checked) d.accent35 else d.line, shape),
            contentAlignment = Alignment.Center,
        ) {
            DeckText(if (checked) "ON" else "OFF", DeckType.small, if (checked) d.textBright else d.textDim, glow = if (checked) d.glowText else null)
        }
    }
}

/**
 * A value stepped with buttons (ZOOM): − and + either side of it, RESET
 * after. Each is a button to accessibility services, named for what it does
 * from [what] ("zoom": "zoom out", "zoom in", "reset the zoom to 100%"); a
 * button that can't go further is dimmed and does nothing. The value is
 * read out as it changes.
 */
@Composable
fun Stepper(
    value: String,
    what: String,
    canDown: Boolean,
    canUp: Boolean,
    canReset: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalDeck.current
    val shape = RoundedCornerShape(DeckSize.radius)
    Row(modifier.fillMaxWidth().height(DeckSize.control), horizontalArrangement = Arrangement.spacedBy(DeckSize.sm)) {
        StepButton("−", "$what out", canDown, onDown, Modifier.width(DeckSize.control))
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .border(DeckSize.hairline, d.line, shape)
                .semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center,
        ) {
            DeckText(value, DeckType.control, d.textBright, glow = d.glowText, maxLines = 1)
        }
        StepButton("+", "$what in", canUp, onUp, Modifier.width(DeckSize.control))
        StepButton("RESET", "reset the $what to 100%", canReset, onReset)
    }
}

/** One of a stepper's buttons: its [label] is what accessibility services read (not the sign on it). */
@Composable
private fun StepButton(text: String, label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val d = LocalDeck.current
    val shape = RoundedCornerShape(DeckSize.radius)
    Box(
        modifier
            .fillMaxHeight()
            .background(d.surface, shape)
            .border(DeckSize.hairline, d.line, shape)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = DeckSize.md),
        contentAlignment = Alignment.Center,
    ) {
        DeckText(text, DeckType.control, if (enabled) d.text else d.textDim, Modifier.clearAndSetSemantics {}, maxLines = 1)
    }
}

/** A button: a framed label; the primary one lit (accent, glowing). */
@Composable
fun DeckButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, label: String? = null) {
    val d = LocalDeck.current
    val shape = RoundedCornerShape(DeckSize.radius)
    Box(
        modifier
            .heightIn(min = DeckSize.control)
            .drawBehind {
                if (primary) for (k in 1..3) drawRoundRect(d.accent.copy(alpha = 0.12f / k), Offset(-k * 2f, -k * 2f), Size(size.width + k * 4f, size.height + k * 4f), CornerRadius(4f), style = Stroke(2f))
            }
            .background(d.surface, shape)
            .border(DeckSize.hairline, if (primary) d.accent35 else d.line, shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = DeckSize.lg),
        contentAlignment = Alignment.Center,
    ) {
        DeckText(text, if (primary) DeckType.action else DeckType.control, if (primary) d.accent else d.text, glow = if (primary) d.glowText else null, maxLines = 1)
    }
}
