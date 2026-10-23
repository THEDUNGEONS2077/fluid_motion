/*
 * lib/neural/NeuralSim.kt: the neural net's scene and motion, a port of
 * cyberdeck_terminal's (src/wallpaper/neural-net/: params.ts, engine.ts),
 * which was matched to its reference ("neural net pape.jpg", a plexus).
 *
 * Layer: lib (pure Kotlin). The scene is built once per screen size: node
 * homes (an even scatter plus loose clusters, past the screen's edges so
 * links run off it), links to near neighbours (a few hubs to many), and
 * out-of-focus particles (bokeh). [advance] moves it on: each node drifts on
 * a slow loop round its home, the bokeh rise, and now and then a signal runs
 * along a few links and lights each node it reaches. The renderer
 * (wallpaper/neural/NeuralRenderer.kt) draws it; colours come from the
 * palette along a diagonal ([shade]).
 *
 * On a phone, counts are per screen, not per area: at the web's density a
 * portrait phone would show only a handful of nodes. Motion keeps its own
 * phases, advanced by each frame's step and wrapped: no clock goes into a
 * sine, so a wallpaper that runs for months moves as smoothly as on day one.
 */
package com.thedungeons2077.fluidmotion.lib.neural

import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.mixRgb
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import com.thedungeons2077.fluidmotion.lib.rain.Span
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** One glow level: the nodes' halo strength and size (in core radii), and the links' soft second stroke. */
data class NeuralGlow(val halo: Float, val haloSize: Float, val lineGlow: Float)

/** The neural net's numbers (sizes in dp). */
object NeuralParams {
    /** Nodes on one screen's worth of area (more lie past the edges). */
    const val NODES_PER_SCREEN = 64f
    /** Nodes are placed this far past every edge (share of the screen). */
    const val PAD = 0.14f
    /** Loose clusters: x, y (shares of the screen), spread (share of the width), weight. */
    val CLUSTERS = listOf(
        floatArrayOf(0.78f, 0.18f, 0.16f, 0.24f),
        floatArrayOf(0.6f, 0.55f, 0.18f, 0.18f),
        floatArrayOf(0.88f, 0.62f, 0.12f, 0.16f),
        floatArrayOf(0.32f, 0.42f, 0.2f, 0.12f),
    )
    /** The share of nodes scattered evenly (the rest gather round CLUSTERS). */
    const val SCATTER = 0.55f
    /** How many links a node reaches out with, picked at random. */
    val LINKS = intArrayOf(2, 2, 3, 3, 3, 4, 4, 6, 9)
    /** The longest link, share of the screen's diagonal. */
    const val MAX_LINK = 0.42f
    /** A node picks its links from this many times as many of its nearest neighbours. */
    const val LINK_POOL = 3
    /** A node's drift round its home: amplitude (dp) and period (s). */
    val DRIFT = Span(10f, 38f)
    val DRIFT_PERIOD = Span(26f, 70f)
    /** A node's core radius (dp). */
    val NODE_RADIUS = Span(1.3f, 3.6f)
    /** A link's opacity: short links up to the first, the longest down to the second (then times its weight). */
    const val LINE_ALPHA_SHORT = 0.6f
    const val LINE_ALPHA_LONG = 0.16f
    /** Each link's own brightness range. */
    val LINK_WEIGHT = Span(0.35f, 1.6f)
    /** A link's width (dp). */
    const val LINE_WIDTH = 0.9f
    /** Bokeh on one screen, radius (dp), opacity and rising speed (dp/s). */
    const val BOKEH_PER_SCREEN = 30f
    val BOKEH_RADIUS = Span(4f, 24f)
    val BOKEH_ALPHA = Span(0.05f, 0.3f)
    val BOKEH_SPEED = Span(2f, 9f)
    /** Signals: new ones a second, speed (dp/s), links they run, and how a reached node lights (rise, fade; s). */
    const val PULSES_PER_SECOND = 1.1f
    val PULSE_SPEED = Span(240f, 460f)
    val PULSE_HOPS = Span(1f, 4f)
    const val FLASH_RISE = 0.12f
    const val FLASH_SECONDS = 0.8f
    /** Colours along the diagonal, in this many steps. */
    const val COLOR_STEPS = 32
    /** Signals in flight at most (a fixed pool: no allocation per frame). */
    const val MAX_PULSES = 32

    /** The glow levels. */
    val GLOW = mapOf(
        GlowLevel.LOW to NeuralGlow(0.35f, 4f, 0f),
        GlowLevel.MEDIUM to NeuralGlow(0.5f, 5f, 0.08f),
        GlowLevel.HIGH to NeuralGlow(0.65f, 6.5f, 0.14f),
        GlowLevel.MAX to NeuralGlow(0.8f, 8f, 0.22f),
    )
}

/**
 * Where a point sits on the colour diagonal (0 top left … 1 bottom right),
 * from its place as shares of the screen; weighted to the height.
 */
fun diagonal(x: Float, y: Float): Float = (1.05f * y + 0.35f * x - 0.2f).coerceIn(0f, 1f)

/** The palette's colour at [t] along the diagonal: --cool (lifted towards white) to --text-bright, then to --accent. */
fun shade(c: PaletteColors, t: Float): Int {
    val turn = 0.3f
    val full = 0.64f
    fun smooth(u: Float) = u * u * (3 - 2 * u)
    val cool = mixRgb(c.cool, 0xffffff, 0.4f)
    return if (t < turn) mixRgb(cool, c.textBright, smooth(t / turn))
    else mixRgb(c.textBright, c.accent, smooth(min(1f, (t - turn) / (full - turn))))
}

/**
 * Links each point to want[i] of its neighbours within [maxLen] (each pair
 * once, a < b), picked at random from its nearest want[i] × LINK_POOL: most
 * links fairly short, some reaching far. Returns pairs, flat: [a0, b0, a1, b1, …].
 */
fun linkNodes(xs: FloatArray, ys: FloatArray, want: IntArray, maxLen: Float, rng: Rng): IntArray {
    val n = xs.size
    val seen = HashSet<Long>()
    val out = ArrayList<Int>()
    val near = ArrayList<Pair<Float, Int>>()
    for (i in 0 until n) {
        near.clear()
        for (j in 0 until n) {
            if (j == i) continue
            val d = hypot(xs[j] - xs[i], ys[j] - ys[i])
            if (d <= maxLen) near.add(d to j)
        }
        near.sortBy { it.first }
        val pool = ArrayList(near.subList(0, min(near.size, want[i] * NeuralParams.LINK_POOL)))
        // A random pick from the pool (shuffled in place, the first `want` taken).
        for (k in pool.size - 1 downTo 1) {
            val r = (rng.next() * (k + 1)).toInt()
            val t = pool[k]
            pool[k] = pool[r]
            pool[r] = t
        }
        for ((_, j) in pool.take(want[i])) {
            val a = min(i, j)
            val b = maxOf(i, j)
            if (seen.add(a.toLong() * n + b)) {
                out.add(a)
                out.add(b)
            }
        }
    }
    return out.toIntArray()
}

/** The neural net on a [w] × [h] dp screen. */
class NeuralSim(val w: Float, val h: Float, private val rng: Rng) {
    /** Nodes: homes, drift (amplitude, angular speed, phase), core radius, light (flash) and whether it's rising; where they are now. */
    val n: Int
    val hx: FloatArray
    val hy: FloatArray
    private val ax: FloatArray
    private val ay: FloatArray
    private val wx: FloatArray
    private val wy: FloatArray
    private val px: FloatArray
    private val py: FloatArray
    val radius: FloatArray
    val flash: FloatArray
    private val rise: BooleanArray
    val x: FloatArray
    val y: FloatArray

    /** Links as pairs [a0, b0, a1, b1, …], each with its own brightness. */
    val links: IntArray
    val weight: FloatArray
    /** Each node's links (indexes into the pair list). */
    private val linksOf: Array<IntArray>

    /** Bokeh: place, radius, opacity, velocity. */
    val nb: Int
    val bx: FloatArray
    val by: FloatArray
    val br: FloatArray
    val ba: FloatArray
    private val bvx: FloatArray
    private val bvy: FloatArray

    /** Signals in flight (a fixed pool): link, from which node, how far along (0–1), speed (share of the link a second), links left. */
    val pulseLink = IntArray(NeuralParams.MAX_PULSES)
    val pulseFrom = IntArray(NeuralParams.MAX_PULSES)
    val pulseAlong = FloatArray(NeuralParams.MAX_PULSES)
    private val pulseSpeed = FloatArray(NeuralParams.MAX_PULSES)
    private val pulseHops = IntArray(NeuralParams.MAX_PULSES)
    /** On its first link (it fades in there). */
    val pulseFirst = BooleanArray(NeuralParams.MAX_PULSES)
    var pulses = 0
        private set
    private var pulseDebt = 0f

    init {
        val p = NeuralParams
        val area = (1 + 2 * p.PAD) * (1 + 2 * p.PAD)
        n = maxOf(12, (p.NODES_PER_SCREEN * area).roundToInt())
        hx = FloatArray(n); hy = FloatArray(n); ax = FloatArray(n); ay = FloatArray(n)
        wx = FloatArray(n); wy = FloatArray(n); px = FloatArray(n); py = FloatArray(n)
        radius = FloatArray(n); flash = FloatArray(n); rise = BooleanArray(n); x = FloatArray(n); y = FloatArray(n)
        val want = IntArray(n)
        val weights = p.CLUSTERS.sumOf { it[3].toDouble() }.toFloat()
        for (i in 0 until n) {
            if (rng.next() < p.SCATTER) {
                hx[i] = -p.PAD * w + rng.next() * w * (1 + 2 * p.PAD)
                hy[i] = -p.PAD * h + rng.next() * h * (1 + 2 * p.PAD)
            } else {
                var pick = rng.next() * weights
                val c = p.CLUSTERS.firstOrNull { pick -= it[3]; pick < 0 } ?: p.CLUSTERS[0]
                // A rough normal (two uniforms) round the cluster's centre.
                hx[i] = c[0] * w + (rng.next() + rng.next() - 1) * c[2] * w
                hy[i] = c[1] * h + (rng.next() + rng.next() - 1) * c[2] * w
            }
            ax[i] = rng.between(p.DRIFT)
            ay[i] = rng.between(p.DRIFT)
            // Slightly different speeds across and down: each node traces a slow, open loop.
            wx[i] = (2 * PI / rng.between(p.DRIFT_PERIOD)).toFloat()
            wy[i] = wx[i] * (0.7f + rng.next() * 0.6f)
            px[i] = (rng.next() * 2 * PI).toFloat()
            py[i] = (rng.next() * 2 * PI).toFloat()
            want[i] = p.LINKS[(rng.next() * p.LINKS.size).toInt()]
            // Hubs are the bigger nodes.
            radius[i] = p.NODE_RADIUS.start + (p.NODE_RADIUS.endInclusive - p.NODE_RADIUS.start) * min(1f, (want[i] - 2) / 5f + rng.next() * 0.35f)
        }
        for (i in 0 until n) {
            x[i] = hx[i] + ax[i] * sin(px[i])
            y[i] = hy[i] + ay[i] * sin(py[i])
        }
        links = linkNodes(hx, hy, want, p.MAX_LINK * hypot(w, h), rng)
        weight = FloatArray(links.size / 2) { rng.between(p.LINK_WEIGHT) }
        val lists = Array(n) { ArrayList<Int>() }
        for (k in 0 until links.size / 2) {
            lists[links[2 * k]].add(k)
            lists[links[2 * k + 1]].add(k)
        }
        linksOf = Array(n) { lists[it].toIntArray() }
        nb = (p.BOKEH_PER_SCREEN).roundToInt()
        bx = FloatArray(nb); by = FloatArray(nb); br = FloatArray(nb); ba = FloatArray(nb); bvx = FloatArray(nb); bvy = FloatArray(nb)
        for (i in 0 until nb) {
            bx[i] = rng.next() * w
            by[i] = rng.next() * h
            br[i] = rng.between(p.BOKEH_RADIUS)
            ba[i] = rng.between(p.BOKEH_ALPHA)
            // Rising, a little sideways, like dust in warm air.
            val speed = rng.between(p.BOKEH_SPEED)
            val angle = (-PI / 2 + (rng.next() - 0.5) * 1.6).toFloat()
            bvx[i] = cos(angle) * speed
            bvy[i] = sin(angle) * speed
        }
    }

    /** Moves everything on by [dt] seconds. */
    fun advance(dt: Float) {
        val p = NeuralParams
        for (i in 0 until n) {
            // Each drift's phase, kept in one turn.
            px[i] = (px[i] + wx[i] * dt) % TAU
            py[i] = (py[i] + wy[i] * dt) % TAU
            x[i] = hx[i] + ax[i] * sin(px[i])
            y[i] = hy[i] + ay[i] * sin(py[i])
            // A lit node's light rises quickly, then fades slowly.
            if (rise[i]) {
                flash[i] = min(1f, flash[i] + dt / p.FLASH_RISE)
                if (flash[i] >= 1f) rise[i] = false
            } else {
                flash[i] = maxOf(0f, flash[i] - dt / p.FLASH_SECONDS)
            }
        }
        // Bokeh drift, wrapping round the screen.
        for (i in 0 until nb) {
            val r = br[i]
            bx[i] = wrap(bx[i] + bvx[i] * dt, -r, w + r)
            by[i] = wrap(by[i] + bvy[i] * dt, -r, h + r)
        }
        // New signals, from a random node with links.
        pulseDebt += p.PULSES_PER_SECOND * dt
        while (pulseDebt >= 1f && links.isNotEmpty()) {
            pulseDebt -= 1f
            if (pulses >= p.MAX_PULSES) continue
            val k = (rng.next() * (links.size / 2)).toInt()
            val from = if (rng.next() < 0.5f) links[2 * k] else links[2 * k + 1]
            rise[from] = true
            pulseLink[pulses] = k
            pulseFrom[pulses] = from
            pulseAlong[pulses] = 0f
            pulseSpeed[pulses] = 0f
            pulseHops[pulses] = rng.between(p.PULSE_HOPS).roundToInt() - 1
            pulseFirst[pulses] = true
            pulses++
        }
        // Signals run on; reaching the far node lights it, and may carry on down another of its links.
        var i = 0
        while (i < pulses) {
            val k = pulseLink[i]
            val a = links[2 * k]
            val b = links[2 * k + 1]
            if (pulseSpeed[i] == 0f) pulseSpeed[i] = rng.between(p.PULSE_SPEED) / maxOf(1f, hypot(x[b] - x[a], y[b] - y[a]))
            pulseAlong[i] += pulseSpeed[i] * dt
            if (pulseAlong[i] < 1f) {
                i++
                continue
            }
            val to = if (pulseFrom[i] == a) b else a
            rise[to] = true
            // Another of its links, picked at random (not the one it came along): no list built.
            val others = linksOf[to]
            val choices = others.size - (if (k in others) 1 else 0)
            if (pulseHops[i] > 0 && choices > 0) {
                var pick = (rng.next() * choices).toInt()
                var next = others[0]
                for (o in others) {
                    if (o == k) continue
                    if (pick-- == 0) {
                        next = o
                        break
                    }
                }
                pulseLink[i] = next
                pulseFrom[i] = to
                pulseAlong[i] = 0f
                pulseSpeed[i] = 0f
                pulseHops[i]--
                pulseFirst[i] = false
                i++
            } else {
                // Done: the last one in the pool takes its place.
                pulses--
                pulseLink[i] = pulseLink[pulses]; pulseFrom[i] = pulseFrom[pulses]; pulseAlong[i] = pulseAlong[pulses]
                pulseSpeed[i] = pulseSpeed[pulses]; pulseHops[i] = pulseHops[pulses]; pulseFirst[i] = pulseFirst[pulses]
            }
        }
    }

    /** [v] wrapped into [lo, hi). */
    private fun wrap(v: Float, lo: Float, hi: Float): Float {
        val span = hi - lo
        var u = (v - lo) % span
        if (u < 0) u += span
        return lo + u
    }

    /** Where signal [i] is now (dp): x, then y. */
    fun pulseX(i: Int): Float {
        val from = pulseFrom[i]
        return x[from] + (x[pulseTo(i)] - x[from]) * pulseAlong[i]
    }

    fun pulseY(i: Int): Float {
        val from = pulseFrom[i]
        return y[from] + (y[pulseTo(i)] - y[from]) * pulseAlong[i]
    }

    /** The node signal [i] is running to. */
    private fun pulseTo(i: Int): Int {
        val k = pulseLink[i]
        val a = links[2 * k]
        return if (pulseFrom[i] == a) links[2 * k + 1] else a
    }

    /** The longest link allowed on this screen (dp). */
    val maxLink: Float get() = NeuralParams.MAX_LINK * hypot(w, h)

    private companion object {
        /** A full turn (radians). */
        const val TAU = (2 * PI).toFloat()
    }
}
