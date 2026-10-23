/*
 * lib/Glow.kt: how strongly a wallpaper glows (LOW, MEDIUM, HIGH, MAX), as in
 * cyberdeck_terminal's SYSTEM ▸ APPEARANCE ▸ GLOW.
 *
 * Layer: lib (pure Kotlin). Each wallpaper reads the level its own way (the
 * rain's glyph glow, the neural net's halos, velocity's brightness).
 */
package com.thedungeons2077.fluidmotion.lib

/** The glow levels, weakest first. Their names are stored in settings: never rename one. */
enum class GlowLevel(val label: String) {
    LOW("LOW"),
    MEDIUM("MEDIUM"),
    HIGH("HIGH"),
    MAX("MAX");

    companion object {
        /** The level a new install starts with (cyberdeck_terminal's default too). */
        val DEFAULT = HIGH

        /** The level named [name], or the default. */
        fun find(name: String?): GlowLevel = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
