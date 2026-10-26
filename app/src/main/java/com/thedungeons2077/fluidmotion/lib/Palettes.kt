/*
 * lib/Palettes.kt: the colour palettes, the same eight as cyberdeck_terminal
 * (src/lib/palettes.ts there): PHOSPHOR, AMBER, ICE, NEON, BLOOD, AZURE,
 * SYNTH and OUTRUN (0.8.0, made there for HORIZON and SUNSET).
 *
 * Layer: lib (pure Kotlin, no Android). A palette is the handful of base
 * colours everything is built from: the app's UI tokens (ui/theme) and the
 * wallpapers' colours alike. Each wallpaper has its own palette, chosen apart
 * from the app's, as in cyberdeck_terminal.
 *
 * The design rule holds for every palette: every text colour (dim, text,
 * accent, bright) passes WCAG AA (4.5:1) on every background it's drawn on,
 * and bright text on the wash. PalettesTest checks it.
 */
package com.thedungeons2077.fluidmotion.lib

import kotlin.math.pow
import kotlin.math.roundToInt

/** A palette's base colours, as 0xRRGGBB. */
data class PaletteColors(
    /** --bg: behind everything. */
    val bg: Int,
    /** --surface, --surface-raised: panels, and panels on panels. */
    val surface: Int,
    val surfaceRaised: Int,
    /** --line: frames and rules; --line-lit: a lit frame. */
    val line: Int,
    val lineLit: Int,
    /** --xp-wash: a selected thing's background. */
    val wash: Int,
    /** Text, in rising importance. */
    val textDim: Int,
    val text: Int,
    val accent: Int,
    /** The accent's dark end. */
    val accentDeep: Int,
    val textBright: Int,
    /** A second, contrasting hue (two-tone wallpapers). */
    val cool: Int,
)

/** A palette: its id (stored in settings: never change one), its name, its colours. */
data class Palette(val id: String, val label: String, val colors: PaletteColors)

/** The palettes, in the order the app lists them. PHOSPHOR is the original green. */
object Palettes {
    val PHOSPHOR = Palette(
        "phosphor", "PHOSPHOR",
        PaletteColors(0x000000, 0x060d08, 0x0b160d, 0x12301a, 0x145907, 0x092903, 0x2b903f, 0x2fb344, 0x39ff14, 0x1f8f2c, 0xb6ffb0, 0x1d7f8f),
    )
    val AMBER = Palette(
        "amber", "AMBER",
        PaletteColors(0x000000, 0x0d0904, 0x171006, 0x3a2a0e, 0x5a3c06, 0x291a00, 0xa8761c, 0xd29a2a, 0xffb000, 0xa06c00, 0xffe2a8, 0x2a8a9a),
    )
    val ICE = Palette(
        "ice", "ICE",
        PaletteColors(0x000000, 0x040b0d, 0x071417, 0x0f2f36, 0x075564, 0x00262c, 0x1f8ea3, 0x2db5cc, 0x1ee8ff, 0x0a7f8f, 0xb9f5ff, 0x7656ff),
    )
    val NEON = Palette(
        "neon", "NEON",
        PaletteColors(0x000000, 0x0c040b, 0x160714, 0x3a1033, 0x5c0a4c, 0x2a0024, 0xcc4fb0, 0xea6ad0, 0xff3fdc, 0x9a1a82, 0xffc6f2, 0x3a6bff),
    )
    val BLOOD = Palette(
        "blood", "BLOOD",
        PaletteColors(0x000000, 0x0d0405, 0x170709, 0x3a1216, 0x5c0a10, 0x2c0003, 0xd4545e, 0xe86a72, 0xff3040, 0xa0181f, 0xffc8cc, 0x1f6894),
    )
    val AZURE = Palette(
        "azure", "AZURE",
        PaletteColors(0x000000, 0x04080f, 0x08101c, 0x12264a, 0x0b3a7a, 0x001a40, 0x3d84d0, 0x55a2ee, 0x3ca0ff, 0x1a5fb0, 0xcfe4ff, 0x0b3c99),
    )
    /** Hot pink over teal: HORIZON's reference, a synthwave sun over a wireframe land. */
    val SYNTH = Palette(
        "synth", "SYNTH",
        PaletteColors(0x000000, 0x0c0408, 0x160710, 0x3a1027, 0x5c0a33, 0x2a0016, 0xd0568c, 0xee6fa6, 0xff3d8b, 0xa01d55, 0xffd0e2, 0x2fb8b4),
    )
    /** A golden sun melting into hot pink, over blue: SUNSET's reference, the outrun sun. */
    val OUTRUN = Palette(
        "outrun", "OUTRUN",
        PaletteColors(0x000000, 0x0a0510, 0x140a1c, 0x2e1640, 0x4a1a5c, 0x26082e, 0xd65a9c, 0xf27cb4, 0xff3e7f, 0xa3204f, 0xffd75e, 0x3a8dff),
    )

    /** Every palette, in order. */
    val ALL: List<Palette> = listOf(PHOSPHOR, AMBER, ICE, NEON, BLOOD, AZURE, SYNTH, OUTRUN)

    /** The palette with [id], or PHOSPHOR if it's unknown (settings from a newer version, say). */
    fun find(id: String?): Palette = ALL.firstOrNull { it.id == id } ?: PHOSPHOR
}

/** A colour's relative luminance (WCAG 2), from 0xRRGGBB. */
fun luminance(rgb: Int): Double {
    fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel((rgb shr 16) and 255) + 0.7152 * channel((rgb shr 8) and 255) + 0.0722 * channel(rgb and 255)
}

/** The WCAG contrast ratio between two colours (1 to 21). */
fun contrast(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** A colour as three floats in 0–1 (for shaders). */
fun rgbFloats(rgb: Int): FloatArray = floatArrayOf(((rgb shr 16) and 255) / 255f, ((rgb shr 8) and 255) / 255f, (rgb and 255) / 255f)

/** The colour [t] of the way from [a] to [b] (each 0xRRGGBB). */
fun mixRgb(a: Int, b: Int, t: Float): Int {
    fun ch(shift: Int): Int {
        val x = (a shr shift) and 255
        val y = (b shr shift) and 255
        // Rounded, as the web's mixes are (CSS color-mix, the engines' rgba()).
        return (x + (y - x) * t).roundToInt().coerceIn(0, 255)
    }
    return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * [rgb] pulled toward its own grey by [amount] (0–1): grey is its luma, so
 * it keeps its brightness and loses colour (the rain's muted bodies:
 * glyphAtlas.ts desaturate).
 */
fun desaturate(rgb: Int, amount: Float): Int {
    val r = (rgb shr 16) and 255
    val g = (rgb shr 8) and 255
    val b = rgb and 255
    val grey = 0.299f * r + 0.587f * g + 0.114f * b
    fun mix(c: Int) = (c + (grey - c) * amount).roundToInt().coerceIn(0, 255)
    return (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
}
