/*
 * lib/flow/FlowBoard.kt: DATA FLOW's circuit board: chips, their pins, the
 * traces between, laid out once per screen. A port of cyberdeck_terminal's
 * (src/wallpaper/data-flow/board.ts), rule for rule.
 *
 * Layer: lib (pure Kotlin, seeded: unit tested). The board is a grid of
 * points PITCH apart, a few past every edge of the screen; a trace runs
 * from point to point in the eight directions 45° apart, as a printed
 * board's do.
 *   - Chips: the main one a little right of the middle, the others spread
 *     out (each the farthest of several tries from those already down),
 *     each with a ring kept clear and pins every PIN_STEP points.
 *   - Buses: neighbouring pins run out together on one route; before a 90°
 *     bend the outer traces run on further, so a bus bends like a ribbon.
 *   - Loose traces fill the rest (some as small buses from a row of vias)
 *     until COVERAGE of the screen's points carry one.
 * Traces never cross or touch: a point is taken by one trace at most, a
 * diagonal step can't pass between two taken points, and a trace keeps one
 * free point from any other beside it. Blocked, a trace ends in a via;
 * off the board, it ends off screen; blocked at once, it isn't laid.
 * Built once per screen: it may allocate.
 */
package com.thedungeons2077.fluidmotion.lib.flow

import com.thedungeons2077.fluidmotion.lib.flow.FlowParams.PITCH
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** What a trace's end is: a chip's pin, a via, a pad, or off the board. */
enum class End { CHIP, VIA, PAD, EDGE }

/** A chip's pin: its point (just outside the chip's edge), the direction away from the chip, its chip. */
class Pin(val x: Int, val y: Int, val dir: Int, val chip: Int)

/** A chip: its corner point and size (points), whether it's the main one, its pins. */
class Chip(val x: Int, val y: Int, val w: Int, val h: Int, val main: Boolean, val pins: List<Pin>)

/**
 * A trace: its corner points (x0, y0, x1, y1, …: start to end) and the
 * length up to each corner (grid units), its width (dp), its hue, its
 * ends, the chip it leaves (or -1) and its bus (or -1).
 */
class Trace(
    val pts: IntArray,
    val len: FloatArray,
    val width: Float,
    cool: Boolean,
    val start: End,
    val end: End,
    val chip: Int,
    val bus: Int,
) {
    /** In the palette's cool hue (else warm); a board short of cool traces turns some (FlowBoard balanceHues), once, as it's laid out. */
    var cool: Boolean = cool
        internal set

    /** Its whole length (grid units). */
    val length: Float get() = len[len.size - 1]

    /** The point [d] grid units along it (clamped to its ends), into [out] ([x, y], grid units). */
    fun pointAt(d: Float, out: FloatArray) {
        val s = d.coerceIn(0f, length)
        var i = 1
        while (i < len.size - 1 && len[i] < s) i++
        val a = len[i - 1]
        val b = len[i]
        val f = if (b > a) (s - a) / (b - a) else 0f
        out[0] = pts[2 * (i - 1)] + (pts[2 * i] - pts[2 * (i - 1)]) * f
        out[1] = pts[2 * (i - 1) + 1] + (pts[2 * i + 1] - pts[2 * (i - 1) + 1]) * f
    }
}

/** A board: the screen (dp), its points, the chips, the traces, the buses (their traces' indexes). */
class FlowBoard(val w: Float, val h: Float, val cols: Int, val rows: Int, val chips: List<Chip>, val traces: List<Trace>, val buses: List<IntArray>) {
    /** The main chip's centre (dp), where the glow sits. */
    val mainX: Float get() = chips.firstOrNull { it.main }?.let { (it.x + it.w / 2f - 0.5f) * PITCH } ?: (w / 2)
    val mainY: Float get() = chips.firstOrNull { it.main }?.let { (it.y + it.h / 2f - 0.5f) * PITCH } ?: (h / 2)

    companion object {
        /** The eight directions, 45° apart, clockwise from east (screen y runs down). */
        val DIRS: Array<IntArray> = arrayOf(
            intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1),
            intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1),
        )

        /** Lays out a board for a screen of [w] × [h] dp. */
        fun build(w: Float, h: Float, rng: Rng): FlowBoard = Builder(w, h, rng).build()
    }
}

/** One segment of a route: a direction, how many steps, and (a straight run before a 90° bend) the bend's way. */
private class Seg(val dir: Int, val steps: Int, var bend: Int = 0)

/** The layout, step by step (one use). */
private class Builder(private val w: Float, private val h: Float, private val rng: Rng) {
    private val p = FlowParams
    private val dirs = FlowBoard.DIRS
    private val sw = ceil(w / PITCH).toInt()
    private val sh = ceil(h / PITCH).toInt()
    private val m = p.MARGIN
    private val cols = sw + 2 * m + 1
    private val rows = sh + 2 * m + 1

    // Each point: 0 free, -1 a chip or its clear ring, t + 1 trace t.
    private val occ = IntArray(cols * rows)
    private val screenPoints = (sw + 1) * (sh + 1)
    private var covered = 0
    private val chips = ArrayList<Chip>()
    private val traces = ArrayList<Trace>()
    private val buses = ArrayList<IntArray>()

    // The points the trace being laid has taken (to give back if it comes out too short).
    private val taken = ArrayList<Int>()

    private fun at(x: Int, y: Int) = x + m + (y + m) * cols
    private fun inBoard(x: Int, y: Int) = x >= -m && y >= -m && x <= sw + m && y <= sh + m
    private fun onScreen(x: Int, y: Int) = x >= 0 && y >= 0 && x <= sw && y <= sh
    private fun pick(r: IntRange) = r.first + floor(rng.next() * (r.last - r.first + 1)).toInt()

    /** Whether a chip of cw × ch points centred near (cx, cy) fits: it and a three-point ring round it free and on screen. */
    private fun fits(cw: Int, ch: Int, cx: Float, cy: Float): Boolean {
        val x = (cx - cw / 2f).roundToInt()
        val y = (cy - ch / 2f).roundToInt()
        for (gy in y - 3..y + ch + 2) for (gx in x - 3..x + cw + 2) if (!onScreen(gx, gy) || occ[at(gx, gy)] != 0) return false
        return true
    }

    /** Places a chip centred near (cx, cy), if it fits. */
    private fun placeChip(cw: Int, ch: Int, cx: Float, cy: Float, main: Boolean): Boolean {
        if (!fits(cw, ch, cx, cy)) return false
        val x = (cx - cw / 2f).roundToInt()
        val y = (cy - ch / 2f).roundToInt()
        // The chip and the ring its pins sit in are kept clear of other traces.
        for (gy in y - 1..y + ch) for (gx in x - 1..x + cw) occ[at(gx, gy)] = -1
        val index = chips.size
        val pins = ArrayList<Pin>()
        for (i in 1..cw - 2 step p.PIN_STEP) pins += Pin(x + i, y - 1, 6, index)
        for (j in 1..ch - 2 step p.PIN_STEP) pins += Pin(x + cw, y + j, 0, index)
        for (i in 1..cw - 2 step p.PIN_STEP) pins += Pin(x + i, y + ch, 2, index)
        for (j in 1..ch - 2 step p.PIN_STEP) pins += Pin(x - 1, y + j, 4, index)
        chips += Chip(x, y, cw, ch, main, pins)
        return true
    }

    /** One step from (x, y) towards [dir] for trace [t]: the point, EDGE_STEP off the board, or BLOCKED. */
    private fun step(t: Int, x: Int, y: Int, dir: Int): Long {
        val dx = dirs[dir][0]
        val dy = dirs[dir][1]
        val nx = x + dx
        val ny = y + dy
        if (!inBoard(nx, ny)) return EDGE_STEP
        if (occ[at(nx, ny)] != 0) return BLOCKED
        // A diagonal step can't pass between two taken points (it would cross a trace).
        if (dx != 0 && dy != 0) {
            val a = if (inBoard(nx, y)) occ[at(nx, y)] else 0
            val b = if (inBoard(x, ny)) occ[at(x, ny)] else 0
            if (a > 0 && b > 0) return BLOCKED
        }
        // One free point from any other trace beside it.
        for (k in 0 until 4) {
            val ox = SIDES[2 * k]
            val oy = SIDES[2 * k + 1]
            if (!inBoard(nx + ox, ny + oy)) continue
            val v = occ[at(nx + ox, ny + oy)]
            if (v > 0 && v != t + 1) return BLOCKED
        }
        return pack(nx, ny)
    }

    private fun take(t: Int, x: Int, y: Int) {
        occ[at(x, y)] = t + 1
        taken += at(x, y)
        if (onScreen(x, y)) covered++
    }

    /** Gives back every point the trace being laid has taken. */
    private fun giveBack() {
        for (i in taken) {
            occ[i] = 0
            if (onScreen(i % cols - m, i / cols - m)) covered--
        }
        taken.clear()
    }

    /** A walk's result: its corners, how it ended, how many steps it took. */
    private class Walk(val pts: IntArray, val end: End, val steps: Int)

    /** Walks trace [t] from (x0, y0) as [next] directs (a direction, or -1 to stop), taking points. */
    private fun walk(t: Int, x0: Int, y0: Int, maxSteps: Int, next: () -> Int): Walk {
        val pts = arrayListOf(x0, y0)
        var x = x0
        var y = y0
        var lastDir = -1
        var end = End.VIA
        var steps = 0
        while (steps < maxSteps) {
            val dir = next()
            if (dir < 0) break
            val s = step(t, x, y, dir)
            if (s == EDGE_STEP) {
                end = End.EDGE
                break
            }
            if (s == BLOCKED) break
            // A corner where the direction changes.
            if (lastDir != -1 && dir != lastDir) {
                pts += x
                pts += y
            }
            lastDir = dir
            x = unpackX(s)
            y = unpackY(s)
            take(t, x, y)
            steps++
        }
        if (pts[pts.size - 2] != x || pts[pts.size - 1] != y) {
            pts += x
            pts += y
        }
        return Walk(pts.toIntArray(), end, steps)
    }

    private fun addTrace(pts: IntArray, width: Float, cool: Boolean, start: End, end: End, chip: Int, bus: Int): Int {
        val len = FloatArray(pts.size / 2)
        for (i in 1 until len.size) {
            len[i] = len[i - 1] + hypot((pts[2 * i] - pts[2 * i - 2]).toFloat(), (pts[2 * i + 1] - pts[2 * i - 1]).toFloat())
        }
        traces += Trace(pts, len, width, cool, start, end, chip, bus)
        return traces.size - 1
    }

    /** A route out going [dir0]: a first run, then 45° bends with runs between, to about a set length. */
    private fun plan(dir0: Int): List<Seg> {
        val segs = arrayListOf(Seg(dir0, pick(p.ROUTE_FIRST)))
        val length = pick(p.ROUTE_LENGTH)
        var total = segs[0].steps
        var dir = dir0
        while (total < length) {
            val turn = if (rng.next() < 0.5f) 1 else -1
            val diagonal = (dir + turn + 8) % 8
            // Back the way it was going (a jog), or on round to 90°: never back towards the chip.
            var after = if (rng.next() < 0.55f) dir else (dir + 2 * turn + 8) % 8
            if (after == (dir0 + 4) % 8) after = dir
            // The straight run before a 90° bend knows it, so a bus's outer traces can turn later.
            segs[segs.size - 1].bend = if (after == dir) 0 else turn
            val d = pick(p.ROUTE_DIAGONAL)
            val s = pick(p.ROUTE_STRAIGHT)
            segs += Seg(diagonal, d)
            segs += Seg(after, s)
            total += d + s
            dir = after
        }
        return segs
    }

    /**
     * Routes a bus from [starts] (points in a row across the way they run),
     * all going [dir0]; a trace blocked within [minSteps] is given back.
     */
    private fun routeBus(starts: List<IntArray>, dir0: Int, chip: Int, startEnd: End, minSteps: Int) {
        val n = starts.size
        val segs = plan(dir0)
        val cool = rng.next() < p.COOL_SHARE
        val width = rng.between(p.TRACE_WIDTH)
        // Each member's place across the bus, counted from its left (facing the way it runs).
        val rx = dirs[(dir0 + 2) % 8][0]
        val ry = dirs[(dir0 + 2) % 8][1]
        val lateral = starts.map { it[0] * rx + it[1] * ry }
        val order = lateral.sorted()
        val bus = buses.size
        val ids = ArrayList<Int>()
        starts.forEachIndexed { k, start ->
            val lane = order.indexOf(lateral[k])
            // Before a 90° bend, the outer traces run on further.
            val own = segs.map { s -> if (s.bend == 0) s.steps else s.steps + (if (s.bend > 0) n - 1 - lane else lane) * p.PIN_STEP }
            val t = traces.size
            taken.clear()
            take(t, start[0], start[1])
            var si = 0
            var left = own[0]
            val route = walk(t, start[0], start[1], 10_000) {
                while (left <= 0) {
                    si++
                    if (si >= own.size) return@walk -1
                    left = own[si]
                }
                left--
                segs[si].dir
            }
            if (route.steps < minSteps) {
                // Blocked at once: a stub and a via right by the chip would only clutter.
                giveBack()
                if (chip >= 0) occ[at(start[0], start[1])] = -1
                return@forEachIndexed
            }
            ids += addTrace(route.pts, width, cool, if (chip >= 0) End.CHIP else startEnd, route.end, chip, bus)
        }
        if (ids.isNotEmpty()) buses += ids.toIntArray()
    }

    /** Whether (x, y) is free with nothing round it (a loose trace's start). */
    private fun roomy(x: Int, y: Int): Boolean {
        if (!onScreen(x, y)) return false
        for (oy in -1..1) for (ox in -1..1) if (inBoard(x + ox, y + oy) && occ[at(x + ox, y + oy)] != 0) return false
        return true
    }

    fun build(): FlowBoard {
        // 1. Chips: the main one, then the others spread out.
        val mainW = min(pick(p.MAIN_W), (sw * 0.5f).toInt()).coerceAtLeast(4)
        val mainH = min(pick(p.MAIN_H), (sh * 0.3f).toInt()).coerceAtLeast(3)
        placeChip(mainW, mainH, sw * p.MAIN_AT_X, sh * p.MAIN_AT_Y, true)
        val sideCount = maxOf(p.SIDE_CHIPS_MIN, (p.SIDE_CHIPS_PER_MPX * w * h / 1e6f).roundToInt())
        repeat(sideCount) {
            val cw = pick(p.SIDE_W)
            val ch = pick(p.SIDE_H)
            var best: FloatArray? = null
            var bestGap = -1f
            repeat(60) {
                val cx = 3 + rng.next() * (sw - 6)
                val cy = 3 + rng.next() * (sh - 6)
                val gap = chips.minOfOrNull { c -> hypot(c.x + c.w / 2f - cx, c.y + c.h / 2f - cy) } ?: Float.POSITIVE_INFINITY
                if (gap > bestGap && fits(cw, ch, cx, cy)) {
                    best = floatArrayOf(cx, cy)
                    bestGap = gap
                }
            }
            best?.let { placeChip(cw, ch, it[0], it[1], false) }
        }

        // 2. Buses from every chip's pins (the main chip first).
        for ((ci, chip) in chips.withIndex()) {
            for (dir in intArrayOf(6, 0, 2, 4)) {
                val pins = chip.pins.filter { it.dir == dir }
                var i = 0
                while (i < pins.size) {
                    val n = min(pins.size - i, pick(p.BUS))
                    val members = pins.subList(i, i + n)
                    i += n
                    // Some pins stay bare stubs: a board isn't wired everywhere.
                    if (rng.next() < 0.15f) continue
                    routeBus(members.map { intArrayOf(it.x, it.y) }, dir, ci, End.CHIP, p.MIN_STEPS)
                }
            }
        }

        // 3. Loose traces, until the screen is covered enough: some as small buses, the rest alone.
        var tries = 0
        while (covered.toFloat() / screenPoints < p.COVERAGE && tries++ < 4000) {
            val x0 = floor(rng.next() * (sw + 1)).toInt()
            val y0 = floor(rng.next() * (sh + 1)).toInt()
            if (rng.next() < p.LOOSE_BUS_SHARE) {
                val dir0 = floor(rng.next() * 4).toInt() * 2
                val rx = dirs[(dir0 + 2) % 8][0]
                val ry = dirs[(dir0 + 2) % 8][1]
                val n = pick(p.LOOSE_BUS)
                val starts = List(n) { k -> intArrayOf(x0 + rx * k * p.PIN_STEP, y0 + ry * k * p.PIN_STEP) }
                if (!starts.all { roomy(it[0], it[1]) }) continue
                val end = if (rng.next() < 0.4f) End.PAD else End.VIA
                // A loose bus must run a while: short ones read as combs of stubs.
                routeBus(starts, dir0, -1, end, p.MIN_STEPS * 2)
                continue
            }
            val t = traces.size
            if (!roomy(x0, y0)) continue
            taken.clear()
            take(t, x0, y0)
            val length = pick(p.LOOSE_LENGTH)
            // Mostly straight, sometimes diagonal; a diagonal run turns back straight after a few steps.
            var dir = if (rng.next() < 0.7f) floor(rng.next() * 4).toInt() * 2 else floor(rng.next() * 4).toInt() * 2 + 1
            var diagonalLeft = if (dir % 2 == 1) pick(1..4) else 0
            val route = walk(t, x0, y0, length) {
                if (dir % 2 == 1) {
                    if (diagonalLeft-- <= 0) dir = (dir + (if (rng.next() < 0.5f) 1 else -1) + 8) % 8
                } else if (rng.next() < p.LOOSE_TURN) {
                    dir = (dir + (if (rng.next() < 0.5f) 1 else -1) + 8) % 8
                    diagonalLeft = pick(1..4)
                }
                dir
            }
            if (route.steps < 4) {
                // Too short to read as a trace: give its points back.
                giveBack()
                continue
            }
            // Drawn in the web's order (width, hue, then the ends): the same random numbers lay the same board.
            val power = rng.next() < p.POWER_SHARE
            val width = if (power) p.POWER_WIDTH else rng.between(p.TRACE_WIDTH)
            val cool = rng.next() < p.COOL_SHARE
            val start = if (rng.next() < 0.3f) End.PAD else End.VIA
            val end = if (route.end == End.EDGE) End.EDGE else if (rng.next() < 0.3f) End.PAD else End.VIA
            addTrace(route.pts, width, cool, start, end, -1, -1)
        }
        balanceHues()
        return FlowBoard(w, h, cols, rows, chips, traces, buses)
    }

    /**
     * Makes sure at least MIN_COOL_SHARE of the traces' length is cool: if
     * not, the warm buses and loose traces (a bus turns as one) turn cool,
     * smallest first (so as little changes as needed), until it is. No
     * random numbers: every other draw, and so the rest of the board, is as
     * before. As the web's balanceHues, step for step.
     */
    private fun balanceHues() {
        val total = traces.sumOf { it.length.toDouble() }
        var cool = traces.sumOf { if (it.cool) it.length.toDouble() else 0.0 }
        if (total <= 0.0 || cool >= p.MIN_COOL_SHARE * total) return
        // The warm groups: each bus whole, each loose trace alone (in the order first met).
        val groups = LinkedHashMap<String, MutableList<Trace>>()
        traces.forEachIndexed { i, t ->
            if (t.cool) return@forEachIndexed
            groups.getOrPut(if (t.bus >= 0) "b${t.bus}" else "t$i") { mutableListOf() } += t
        }
        // Smallest first; equal ones keep the order they were met in (sortedBy is stable).
        for (g in groups.values.sortedBy { g -> g.sumOf { it.length.toDouble() } }) {
            if (cool >= p.MIN_COOL_SHARE * total) break
            for (t in g) t.cool = true
            cool += g.sumOf { it.length.toDouble() }
        }
    }

    private companion object {
        /** A step's answers besides a point: off the board, or blocked. */
        const val EDGE_STEP = Long.MIN_VALUE
        const val BLOCKED = Long.MIN_VALUE + 1

        /** The four neighbours (x, y pairs). */
        val SIDES = intArrayOf(1, 0, -1, 0, 0, 1, 0, -1)

        /** A point packed in a Long (no pair allocated per step). */
        fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
        fun unpackX(v: Long): Int = (v shr 32).toInt()
        fun unpackY(v: Long): Int = v.toInt()
    }
}
