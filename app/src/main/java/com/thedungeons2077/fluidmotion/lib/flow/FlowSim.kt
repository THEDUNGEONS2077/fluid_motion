/*
 * lib/flow/FlowSim.kt: DATA FLOW's motion: packets of light running along
 * the board's traces, the afterglow they leave, vias flashing as they're
 * reached, chips glowing as data arrives, volleys along whole buses. A
 * port of cyberdeck_terminal's (src/wallpaper/data-flow/engine.ts
 * advance()).
 *
 * Layer: lib (pure Kotlin). Packets and flashes live in fixed pools (a
 * finished one's place is taken by the last): nothing is allocated per
 * frame. Distances are grid units (PITCH dp each), as on the web.
 */
package com.thedungeons2077.fluidmotion.lib.flow

import com.thedungeons2077.fluidmotion.lib.flow.FlowParams.PITCH
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import kotlin.math.max
import kotlin.math.min

/** DATA FLOW's motion on [board]. */
class FlowSim(val board: FlowBoard, private val rng: Rng) {
    private val p = FlowParams

    /** Packets: trace, how far the head has come (units), speed (units/s), tail (units), running end to start, wait before setting off (s), head arrived. */
    val pTrace = IntArray(p.MAX_PULSES)
    val pD = FloatArray(p.MAX_PULSES)
    private val pSpeed = FloatArray(p.MAX_PULSES)
    val pTail = FloatArray(p.MAX_PULSES)
    val pReverse = BooleanArray(p.MAX_PULSES)
    val pWait = FloatArray(p.MAX_PULSES)
    private val pArrived = BooleanArray(p.MAX_PULSES)
    var pulses = 0
        private set

    /** Flashes where packets reached a via or pad: point (grid units), what's left of it (1 → 0), its hue. */
    val fX = FloatArray(p.MAX_FLASHES)
    val fY = FloatArray(p.MAX_FLASHES)
    val fLife = FloatArray(p.MAX_FLASHES)
    val fCool = BooleanArray(p.MAX_FLASHES)
    var flashes = 0
        private set

    /** Each trace's afterglow and each chip's glow (0–1). */
    val heat = FloatArray(board.traces.size)
    val chipGlow = FloatArray(board.chips.size)

    // Picking traces by length (longer carry more): their lengths, summed.
    private val cumulative = FloatArray(board.traces.size)
    private var pulseDebt = 0f
    private var volleyIn = 1f

    init {
        var sum = 0f
        board.traces.forEachIndexed { i, t ->
            sum += t.length
            cumulative[i] = sum
        }
        // Open busy: a little data already running.
        var s = 0f
        while (s < p.PREWARM_SECONDS) {
            advance(1f / 30f)
            s += 1f / 30f
        }
    }

    /** A random trace, the longer the likelier. */
    private fun someTrace(): Int {
        val r = rng.next() * cumulative[cumulative.size - 1]
        var lo = 0
        var hi = cumulative.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] < r) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** A packet's way on [t]: a chip's run in to it (INBOUND of them) or out; a loose trace's either way. */
    private fun way(t: Trace) = if (t.chip >= 0) rng.next() < p.INBOUND else rng.next() < 0.5f

    private fun launch(trace: Int, reverse: Boolean, speed: Float, tail: Float, wait: Float) {
        if (pulses >= p.MAX_PULSES) return
        val i = pulses++
        pTrace[i] = trace
        pD[i] = 0f
        pSpeed[i] = speed
        pTail[i] = tail
        pReverse[i] = reverse
        pWait[i] = wait
        pArrived[i] = false
    }

    /** Moves everything on by [dt] seconds. */
    fun advance(dt: Float) {
        val traces = board.traces
        if (traces.isEmpty()) return
        // New packets: PULSES_PER_SCREEN a second.
        pulseDebt += p.PULSES_PER_SCREEN * dt
        while (pulseDebt >= 1f) {
            pulseDebt -= 1f
            val ti = someTrace()
            launch(ti, way(traces[ti]), rng.between(p.PULSE_SPEED) / PITCH, rng.between(p.PULSE_TAIL) / PITCH, 0f)
        }
        // Now and then a volley along a whole bus: one way, one speed, a little apart.
        volleyIn -= dt
        if (volleyIn <= 0f && board.buses.isNotEmpty()) {
            volleyIn = rng.between(p.VOLLEY_EVERY)
            val bus = board.buses[(rng.next() * board.buses.size).toInt()]
            val reverse = way(traces[bus[0]])
            val speed = rng.between(p.PULSE_SPEED) / PITCH
            val tail = rng.between(p.PULSE_TAIL) / PITCH
            for (ti in bus) launch(ti, reverse, speed, tail, rng.next() * p.VOLLEY_STAGGER)
        }
        // Packets run on; a head reaching the far end flashes its via or lights its chip; a tail all in, it's gone.
        var i = 0
        while (i < pulses) {
            if (pWait[i] > 0f) {
                pWait[i] -= dt
                i++
                continue
            }
            pD[i] += pSpeed[i] * dt
            val t = traces[pTrace[i]]
            // While it runs, its trace warms (the afterglow it leaves).
            heat[pTrace[i]] = min(1f, max(heat[pTrace[i]], 0.6f))
            if (!pArrived[i] && pD[i] >= t.length) {
                pArrived[i] = true
                val end = if (pReverse[i]) t.start else t.end
                val n = t.pts.size
                val x = if (pReverse[i]) t.pts[0] else t.pts[n - 2]
                val y = if (pReverse[i]) t.pts[1] else t.pts[n - 1]
                if ((end == End.VIA || end == End.PAD) && flashes < p.MAX_FLASHES) {
                    fX[flashes] = x.toFloat()
                    fY[flashes] = y.toFloat()
                    fLife[flashes] = 1f
                    fCool[flashes] = t.cool
                    flashes++
                }
                if (end == End.CHIP && t.chip >= 0) chipGlow[t.chip] = min(1f, chipGlow[t.chip] + 0.35f)
            }
            if (pD[i] < t.length + pTail[i]) {
                i++
                continue
            }
            // Its tail all in: the last packet takes its place.
            pulses--
            pTrace[i] = pTrace[pulses]; pD[i] = pD[pulses]; pSpeed[i] = pSpeed[pulses]; pTail[i] = pTail[pulses]
            pReverse[i] = pReverse[pulses]; pWait[i] = pWait[pulses]; pArrived[i] = pArrived[pulses]
        }
        for (k in heat.indices) heat[k] = max(0f, heat[k] - dt / p.AFTERGLOW_SECONDS)
        for (k in chipGlow.indices) chipGlow[k] = max(0f, chipGlow[k] - dt / p.CHIP_GLOW_SECONDS)
        var f = 0
        while (f < flashes) {
            fLife[f] -= dt / p.FLASH_SECONDS
            if (fLife[f] > 0f) {
                f++
                continue
            }
            flashes--
            fX[f] = fX[flashes]; fY[f] = fY[flashes]; fLife[f] = fLife[flashes]; fCool[f] = fCool[flashes]
        }
    }
}
