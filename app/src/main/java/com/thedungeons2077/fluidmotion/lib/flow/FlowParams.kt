/*
 * lib/flow/FlowParams.kt: DATA FLOW's numbers, cyberdeck_terminal's
 * (src/wallpaper/data-flow/params.ts), sizes in dp (CSS px there: the
 * same scale).
 *
 * Layer: lib (data). As with NEURAL NET, packets are counted per screen
 * on a phone, not per area: at the web's density a portrait phone would
 * have one packet every second or so.
 */
package com.thedungeons2077.fluidmotion.lib.flow

import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.rain.Span

/** One glow level: the board's soft glow (dp), the packets' halo (width ×, opacity), their heads' size (dp). */
data class FlowGlow(val board: Float, val halo: Float, val haloAlpha: Float, val head: Float)

/** DATA FLOW's numbers. */
object FlowParams {
    /** The grid's pitch (dp): traces run on its points. */
    const val PITCH = 14f

    /** Points the board runs past every edge (traces run off the screen). */
    const val MARGIN = 4

    /** The main chip: size (points) and where its centre sits (shares of the screen). */
    val MAIN_W = 14..18
    val MAIN_H = 10..13
    const val MAIN_AT_X = 0.62f
    const val MAIN_AT_Y = 0.46f

    /** The other chips: per million dp² (at least SIDE_CHIPS_MIN), and their size (points). */
    const val SIDE_CHIPS_PER_MPX = 1.6f
    const val SIDE_CHIPS_MIN = 2
    val SIDE_W = 5..9
    val SIDE_H = 4..7

    /** A pin every this many points along a chip's edge. */
    const val PIN_STEP = 2

    /** A bus: neighbouring pins running out together. */
    val BUS = 2..6

    /** A route: its first run, its 45° runs, its straight runs, its whole length (points). */
    val ROUTE_FIRST = 2..6
    val ROUTE_DIAGONAL = 1..5
    val ROUTE_STRAIGHT = 4..18
    val ROUTE_LENGTH = 24..90

    /** A trace blocked within this many steps isn't laid (loose buses: twice). */
    const val MIN_STEPS = 3

    /** Loose traces: until this share of the screen's points carry one; as small buses (this share, this size); alone (length, turn chance). */
    const val COVERAGE = 0.16f
    const val LOOSE_BUS_SHARE = 0.5f
    val LOOSE_BUS = 2..4
    val LOOSE_LENGTH = 10..48
    const val LOOSE_TURN = 0.12f

    /** Share of buses and loose traces in the cool hue. */
    const val COOL_SHARE = 0.3f

    /**
     * The least share of a board's traces (by length) in the cool hue
     * (0.12.0, with the web's 0.26.0): a few big buses decide most of a
     * board, so by chance about 1 board in 14 came out almost one colour;
     * a board short of it turns its smallest warm buses and loose traces
     * cool until it isn't (FlowBoard balanceHues).
     */
    const val MIN_COOL_SHARE = 0.2f

    /** Trace widths (dp): signal traces, the odd heavy power trace (its share). */
    val TRACE_WIDTH = Span(0.9f, 1.5f)
    const val POWER_WIDTH = 2.6f
    const val POWER_SHARE = 0.06f

    /** Packets: new ones a second on a phone's screen, speed (dp/s), tail (dp); the share of a chip's that run in to it. */
    const val PULSES_PER_SCREEN = 3.2f
    val PULSE_SPEED = Span(90f, 260f)
    val PULSE_TAIL = Span(40f, 150f)
    const val INBOUND = 0.5f

    /** A volley along a whole bus: every this many seconds; its packets this far apart at most (s). */
    val VOLLEY_EVERY = Span(2.5f, 6f)
    const val VOLLEY_STAGGER = 0.18f

    /** How long an afterglow, a flash and a chip's glow take to fade (s). */
    const val AFTERGLOW_SECONDS = 1.6f
    const val FLASH_SECONDS = 0.6f
    const val CHIP_GLOW_SECONDS = 1.4f

    /** Seconds of packets run before the first frame. */
    const val PREWARM_SECONDS = 2.5f

    /** Packets and flashes in flight at most (fixed pools: nothing allocated per frame). */
    const val MAX_PULSES = 64
    const val MAX_FLASHES = 32

    /** The glow levels. */
    val GLOW = mapOf(
        GlowLevel.LOW to FlowGlow(0f, 3f, 0.16f, 5f),
        GlowLevel.MEDIUM to FlowGlow(2f, 4f, 0.2f, 7f),
        GlowLevel.HIGH to FlowGlow(3f, 5f, 0.24f, 9f),
        GlowLevel.MAX to FlowGlow(5f, 6.5f, 0.3f, 12f),
    )

    /**
     * The glow drawn at [level]: as the table says, or with [linkGlow] off
     * (QUALITY LOW) without the board's soft glow or the packets' halos, the
     * widest of its strokes. Made once per board, not per frame.
     */
    fun glow(level: GlowLevel, linkGlow: Boolean): FlowGlow {
        val g = GLOW.getValue(level)
        return if (linkGlow) g else g.copy(board = 0f, halo = 0f, haloAlpha = 0f)
    }
}
