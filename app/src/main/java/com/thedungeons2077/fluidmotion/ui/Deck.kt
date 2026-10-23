/*
 * ui/Deck.kt: the app's design tokens, cyberdeck_terminal's (theme.css):
 * a palette's base colours, everything else mixed from them (its
 * color-mix tints), one type family (JetBrains Mono), labels in uppercase
 * with letter-spacing, and one effect: a soft glow in the accent, for
 * state (the lit choice, the brand), never decoration.
 *
 * Layer: ui (Compose). Sizes follow the phone's larger type scale (the
 * user's preference from quest_log: readable text, big tap targets).
 */
package com.thedungeons2077.fluidmotion.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thedungeons2077.fluidmotion.R
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.Palettes

/** A palette as Compose colours, with theme.css's mixed tints. */
@Immutable
data class DeckColors(
    val bg: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val line: Color,
    val lineLit: Color,
    val wash: Color,
    val textDim: Color,
    val text: Color,
    val accent: Color,
    val accentDeep: Color,
    val textBright: Color,
    val cool: Color,
) {
    /** --accent-05 … --accent-60: the accent at that much opacity. */
    val accent05 get() = accent.copy(alpha = 0.05f)
    val accent10 get() = accent.copy(alpha = 0.10f)
    val accent22 get() = accent.copy(alpha = 0.22f)
    val accent35 get() = accent.copy(alpha = 0.35f)
    val accent60 get() = accent.copy(alpha = 0.60f)

    /** The glow on lit text (--glow-text). */
    val glowText get() = Shadow(color = accent35, offset = Offset.Zero, blurRadius = 12f)

    /** The brighter glow (--glow-text-bright). */
    val glowTextBright get() = Shadow(color = accent60, offset = Offset.Zero, blurRadius = 12f)
}

/** 0xRRGGBB as an opaque Compose colour. */
fun rgb(c: Int): Color = Color(0xff000000.toInt() or c)

/** A palette's colours as tokens. */
fun PaletteColors.toDeck() = DeckColors(
    bg = rgb(bg), surface = rgb(surface), surfaceRaised = rgb(surfaceRaised), line = rgb(line), lineLit = rgb(lineLit),
    wash = rgb(wash), textDim = rgb(textDim), text = rgb(text), accent = rgb(accent), accentDeep = rgb(accentDeep),
    textBright = rgb(textBright), cool = rgb(cool),
)

/** The tokens in use (the app's own palette, SYSTEM ▸ APP COLOURS). */
val LocalDeck = staticCompositionLocalOf { Palettes.PHOSPHOR.colors.toDeck() }

/** JetBrains Mono, the one family (bundled: the same files as the web's). */
val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** The type scale (sp: it follows the phone's font size setting). */
object DeckType {
    /** The brand: `<fluid_motion>`. */
    val brand = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = (-0.5).sp)
    /** A section's heading. */
    val section = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.5.sp)
    /** A control's label (buttons, choices). */
    val control = TextStyle(fontFamily = Mono, fontSize = 15.sp, letterSpacing = 0.5.sp)
    /** The main action. */
    val action = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp)
    /** Explanations under a control. */
    val detail = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.3.sp)
    /** Small print: the swatch names, the status line. */
    val small = TextStyle(fontFamily = Mono, fontSize = 12.sp, letterSpacing = 1.sp)
}

/** Spacing and sizes (theme.css's --s-*, --control; controls 48 dp for a thumb). */
object DeckSize {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val control = 48.dp
    val radius = 2.dp
    val hairline = 1.dp
}
