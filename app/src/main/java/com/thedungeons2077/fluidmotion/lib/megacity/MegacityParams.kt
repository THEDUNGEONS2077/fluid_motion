/*
 * lib/megacity/MegacityParams.kt: MEGACITY's numbers, cyberdeck_terminal's
 * (src/wallpaper/megacity/params.ts), sizes in dp (CSS px there: the same
 * scale). A city of towers in blue haze at night, seen from a rooftop where
 * a lone figure stands on the ledge, looking out over it.
 *
 * Layer: lib (data). Ranges are pairs of Doubles: the city is laid out in
 * Double, as JavaScript's numbers are, so the phone and the web lay out the
 * same city from the same random numbers (CityGoldenTest).
 */
package com.thedungeons2077.fluidmotion.lib.megacity

import com.thedungeons2077.fluidmotion.lib.GlowLevel

/** A range of Doubles (lo, hi). */
class Range(val lo: Double, val hi: Double)

/**
 * One layer of towers, far to near: tower width and the gap between towers
 * (dp), the chance a tower is left out, where its top can be (shares of the
 * screen's height; below 0 is above it), its window cells (dp) and its share
 * of the haze's colour (fog).
 */
class LayerParams(val width: Range, val gap: Range, val skip: Double, val top: Range, val cellW: Float, val cellH: Float, val fog: Float)

/** One glow level: the windows' halo (0–1), a sign's halo and a light's halo (dp). */
data class CityGlow(val window: Float, val sign: Float, val light: Float)

/** MEGACITY's numbers. */
object MegacityParams {
    /** The layers, far to near: the far ones narrow, tall and pale with haze, the near ones wide, lower and dark. */
    val LAYERS = listOf(
        LayerParams(Range(14.0, 38.0), Range(0.0, 3.0), 0.0, Range(-0.35, 0.3), 3f, 4f, 0.74f),
        LayerParams(Range(18.0, 50.0), Range(0.0, 8.0), 0.1, Range(-0.3, 0.4), 3f, 5f, 0.62f),
        LayerParams(Range(22.0, 62.0), Range(0.0, 14.0), 0.18, Range(-0.25, 0.5), 4f, 5f, 0.52f),
        LayerParams(Range(28.0, 78.0), Range(6.0, 34.0), 0.3, Range(-0.2, 0.62), 4f, 6f, 0.42f),
        LayerParams(Range(40.0, 110.0), Range(24.0, 120.0), 0.45, Range(-0.2, 0.8), 5f, 7f, 0.28f),
    )

    /** A tower that steps back: the chance, its upper part's width (share), how far above it rises (share of the height). */
    const val SETBACK_CHANCE = 0.3
    val SETBACK_WIDTH = Range(0.4, 0.75)
    val SETBACK_RISE = Range(0.04, 0.18)

    /** A spire on a roof on the screen: the chance, how far it rises (share of the height). */
    const val SPIRE_CHANCE = 0.18
    val SPIRE_RISE = Range(0.03, 0.1)

    /** Neon signs (layers 2, 3): how many per million dp², the share upright, sizes (dp), the share in the cool colour. */
    const val SIGNS_PER_MPX = 10.0
    const val SIGNS_UPRIGHT = 0.6
    val SIGN_TALL_W = Range(12.0, 20.0)
    val SIGN_TALL_H = Range(40.0, 110.0)
    val SIGN_WIDE_W = Range(44.0, 96.0)
    val SIGN_WIDE_H = Range(14.0, 24.0)
    const val SIGNS_COOL = 0.3

    /** Pale billboards far off (layer 1): how many per million dp², their size (dp). */
    const val BILLBOARDS_PER_MPX = 3.0
    val BILLBOARD_W = Range(30.0, 70.0)
    val BILLBOARD_H = Range(8.0, 14.0)

    /** Beacons: the chance a roof on the screen has one, how often they blink (s), how long each blink lasts (s). */
    const val BEACON_CHANCE = 0.25
    val BEACON_PERIOD = Range(1.6, 2.6)
    const val BEACON_ON = 0.22f

    /** Drones: one per this many dp of width (within MIN–MAX), speed (dp a second), height (share), tail and size (dp). */
    const val DRONES_PER = 220f
    const val DRONES_MIN = 3
    const val DRONES_MAX = 10
    val DRONE_SPEED = Range(14.0, 46.0)
    val DRONE_Y = Range(0.1, 0.72)
    val DRONE_TAIL = Range(8.0, 26.0)
    val DRONE_SIZE = Range(1.1, 2.0)

    /** Haze patches: how many, size (shares of the screen), height, speed (dp a second), strength. */
    const val HAZE_COUNT = 3
    val HAZE_RX = Range(0.3, 0.55)
    val HAZE_RY = Range(0.05, 0.1)
    val HAZE_Y = Range(0.2, 0.8)
    val HAZE_SPEED = Range(3.0, 8.0)
    val HAZE_ALPHA = Range(0.04, 0.07)

    /** A sign's flicker: the wait between (s), how long (s), how fast (Hz). */
    val FLICKER_EVERY = Range(6.0, 25.0)
    val FLICKER_LENGTH = Range(0.3, 0.8)
    const val FLICKER_RATE = 14f

    /** How long a window stays as it is, on average (s); the clock wraps at a multiple of it, so the windows carry on seamlessly. */
    const val WINDOW_PERIOD = 4000f
    const val TIME_WRAP = WINDOW_PERIOD * 16

    /** The figure on a tall screen and a wide one: her height (share of the screen's), her feet (down), her middle (across). */
    const val TALL_HEIGHT = 0.6
    const val TALL_FEET = 0.855
    const val TALL_X = 0.43
    const val WIDE_HEIGHT = 0.52
    const val WIDE_FEET = 0.875
    const val WIDE_X = 0.3

    /** The ledge runs out past her, the railing's height, where it stops (shares of her height). */
    const val LEDGE_OUT = 0.12
    const val RAIL_HEIGHT = 0.11
    const val RAIL_END = 0.09

    /** The skyline texture's width (texels): a layer's columns wrap onto as many rows as they need. */
    const val SKYLINE_WIDTH = 2048

    /** The glow levels. */
    val GLOW = mapOf(
        GlowLevel.LOW to CityGlow(0f, 6f, 4f),
        GlowLevel.MEDIUM to CityGlow(0.35f, 9f, 6f),
        GlowLevel.HIGH to CityGlow(0.6f, 12f, 8f),
        GlowLevel.MAX to CityGlow(1f, 16f, 11f),
    )
}
