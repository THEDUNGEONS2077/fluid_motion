/*
 * lib/megacity/City.kt: MEGACITY's city, laid out once per screen: the
 * layers of towers, the signs and beacons on them, the rooftop ledge in
 * front; the skyline texture the shader reads; the palette's tones; the
 * foreground's triangles. A port of cyberdeck_terminal's
 * src/wallpaper/megacity/city.ts, rule for rule and in the same order of
 * random draws, in Double as JavaScript's numbers are: the same random
 * numbers lay out the same city (CityGoldenTest).
 *
 * Layer: lib (pure Kotlin, seeded: unit tested). Each layer is a skyline:
 * per column (one dp) the top of the tower there and which tower it is;
 * scene/megacity/MegacityRenderer.kt hands it to the shader (mc_city.frag),
 * which works out every pixel once, windows and all. Built once per
 * screen: it may allocate.
 */
package com.thedungeons2077.fluidmotion.lib.megacity

import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.megacity.MegacityParams.SKYLINE_WIDTH
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A part of a tower (dp, whole numbers): left edge, width, top. */
class Section(val x: Int, val w: Int, val top: Int)

/** A tower: its body, a narrower upper part, a spire (x, top), the seed its look comes from. */
class Tower(val x: Int, val w: Int, val top: Int, val upper: Section?, val spireX: Int, val spireTop: Int, val hasSpire: Boolean, val seed: Int)

/** A neon sign or billboard (dp): where, its layer, the cool colour or the accent, upright or wide, its seed and breathing phase. */
class Sign(val x: Int, val y: Int, val w: Int, val h: Int, val layer: Int, val cool: Boolean, val vertical: Boolean, val seed: Int, val phase: Double)

/** A beacon on a roof or spire: where (dp), its layer, how often it blinks (s) and when. */
class Beacon(val x: Double, val y: Double, val layer: Int, val period: Double, val phase: Double)

/** The rooftop ledge in front (dp): its top, its right end, the top of its glass railing. */
class Ledge(val top: Double, val right: Double, val railTop: Double)

/** A city on a w × h dp screen. */
class City(
    val w: Double,
    val h: Double,
    val cols: Int,
    val layers: List<List<Tower>>,
    val tops: List<FloatArray>,
    val signs: List<Sign>,
    val beacons: List<Beacon>,
    val ledge: Ledge,
) {
    /** Rows of the skyline texture each layer takes. */
    val rows: Int get() = (cols + SKYLINE_WIDTH - 1) / SKYLINE_WIDTH
}

/** MEGACITY's colours (r, g, b each 0–1). */
class CityTones(
    val skyTop: FloatArray,
    val skyMid: FloatArray,
    val skyLow: FloatArray,
    val lowGlow: FloatArray,
    val faceFar: FloatArray,
    val faceNear: FloatArray,
    val edge: FloatArray,
    val winPale: FloatArray,
    val winViolet: FloatArray,
    val winPink: FloatArray,
    val winWhite: FloatArray,
    val signWarm: FloatArray,
    val signCool: FloatArray,
    val beacon: FloatArray,
    val droneWhite: FloatArray,
    val droneCool: FloatArray,
    val seam: FloatArray,
    val ledge: FloatArray,
    val ledgeTop: FloatArray,
    val rail: FloatArray,
    val glass: FloatArray,
)

/** MEGACITY's layout and drawing data. */
object CityLayout {
    /** A column with no tower: no pixel is below it. */
    const val NO_TOWER = 1e6f

    /** Floats per foreground vertex: x, y (dp), r, g, b, a (premultiplied). */
    const val SHAPE = 6

    /** Draws as city.ts's helpers do: a Double in [lo, hi), a whole number in [lo, hi]. */
    private class Draws(val rng: Rng) {
        fun next(): Double = rng.next().toDouble()
        fun between(r: Range): Double = r.lo + (r.hi - r.lo) * next()
        fun pick(r: Range): Int = floor(between(Range(r.lo, r.hi + 1))).toInt()
    }

    /** JavaScript's Math.round (half up), which Java's matches. */
    private fun round(v: Double): Int = Math.round(v).toInt()

    /** Lays out a layer's towers, left to right across the screen. */
    private fun towersFor(layer: Int, w: Double, h: Double, d: Draws): List<Tower> {
        val p = MegacityParams.LAYERS[layer]
        val towers = ArrayList<Tower>()
        var x = -d.pick(Range(0.0, 20.0))
        while (x < w) {
            val tw = d.pick(p.width)
            val gap = d.pick(p.gap)
            if (d.next() >= p.skip) {
                val top = round(d.between(p.top) * h)
                val seed = 1 + floor(d.next() * 0x7fffff).toInt()
                var upper: Section? = null
                // The draw first, then the width (as city.ts: the draw is always made).
                if (d.next() < MegacityParams.SETBACK_CHANCE && tw >= 12) {
                    val uw = max(4, round(tw * d.between(MegacityParams.SETBACK_WIDTH)))
                    val ux = x + floor(d.next() * (tw - uw + 1)).toInt()
                    upper = Section(ux, uw, round(top - d.between(MegacityParams.SETBACK_RISE) * h))
                }
                val roofX = upper?.x ?: x
                val roofW = upper?.w ?: tw
                val roofTop = upper?.top ?: top
                var hasSpire = false
                var spireX = 0
                var spireTop = 0
                // Only a roof on the screen draws for a spire.
                if (roofTop > 0 && d.next() < MegacityParams.SPIRE_CHANCE) {
                    hasSpire = true
                    spireX = roofX + floor(d.next() * roofW).toInt()
                    spireTop = round(roofTop - d.between(MegacityParams.SPIRE_RISE) * h)
                }
                towers += Tower(x, tw, top, upper, spireX, spireTop, hasSpire, seed)
            }
            x += tw + gap
        }
        return towers
    }

    /** A layer's top per column (the tallest part of the tower there). */
    private fun columnTops(towers: List<Tower>, cols: Int): FloatArray {
        val tops = FloatArray(cols) { NO_TOWER }
        fun set(from: Int, width: Int, top: Int) {
            for (c in max(0, from) until min(cols, from + width)) tops[c] = min(tops[c], top.toFloat())
        }
        for (t in towers) {
            set(t.x, t.w, t.top)
            t.upper?.let { set(it.x, it.w, it.top) }
            if (t.hasSpire) set(t.spireX, 1, t.spireTop)
        }
        return tops
    }

    /** Is the stretch of columns [x0, x1] at height y (dp) in sight: no tower of a layer nearer than [layer] covers it? */
    fun inSight(tops: List<FloatArray>, cols: Int, layer: Int, x0: Double, x1: Double, y: Double): Boolean {
        for (l in layer + 1 until tops.size) {
            val t = tops[l]
            for (c in max(0, floor(x0).toInt())..min(cols - 1, floor(x1).toInt())) if (t[c] <= y) return false
        }
        return true
    }

    /** Places signs (layers 2, 3) and billboards (layer 1) on towers, where they can be seen and don't overlap. */
    private fun placeSigns(w: Double, h: Double, cols: Int, layers: List<List<Tower>>, tops: List<FloatArray>, d: Draws): List<Sign> {
        val p = MegacityParams
        val signs = ArrayList<Sign>()
        val mpx = (w * h) / 1e6
        for (billboard in listOf(false, true)) {
            val n = round((if (billboard) p.BILLBOARDS_PER_MPX else p.SIGNS_PER_MPX) * mpx)
            var placed = 0
            var tries = 0
            while (placed < n && tries < n * 40) {
                tries++
                val layer = if (billboard) 1 else 2 + floor(d.next() * 2).toInt()
                val vertical = !billboard && d.next() < p.SIGNS_UPRIGHT
                val sw = d.pick(if (billboard) p.BILLBOARD_W else if (vertical) p.SIGN_TALL_W else p.SIGN_WIDE_W)
                val sh = d.pick(if (billboard) p.BILLBOARD_H else if (vertical) p.SIGN_TALL_H else p.SIGN_WIDE_H)
                val towers = layers[layer]
                val t = towers.getOrNull(floor(d.next() * towers.size).toInt())
                if (t == null || t.w < sw + 4) continue
                val x = t.x + 2 + floor(d.next() * (t.w - sw - 3)).toInt()
                val top = max(t.top, 0) + 6
                val y = round(d.between(Range(top.toDouble(), h * 0.8)))
                // Wholly on the screen, and in sight.
                if (x < 0 || x + sw > w || y + sh > h * 0.85 || !inSight(tops, cols, layer, x - 1.0, x + sw + 1.0, y + sh + 1.0)) continue
                if (signs.any { x < it.x + it.w + 3 && it.x < x + sw + 3 && y < it.y + it.h + 3 && it.y < y + sh + 3 }) continue
                val cool = billboard || d.next() < p.SIGNS_COOL
                val seed = 1 + floor(d.next() * 0x7fffff).toInt()
                val phase = d.next() * Math.PI * 2
                signs += Sign(x, y, sw, sh, layer, cool, vertical, seed, phase)
                placed++
            }
        }
        return signs
    }

    /** Beacons on spires and roofs that can be seen. */
    private fun placeBeacons(h: Double, cols: Int, layers: List<List<Tower>>, tops: List<FloatArray>, d: Draws): List<Beacon> {
        val beacons = ArrayList<Beacon>()
        layers.forEachIndexed { layer, towers ->
            if (layer < 2) return@forEachIndexed
            for (t in towers) {
                val rx = if (t.hasSpire) t.spireX + 0.5 else t.x + t.w / 2.0
                val ry = if (t.hasSpire) t.spireTop.toDouble() else (t.upper?.top ?: t.top).toDouble()
                if (ry <= 2 || ry > h * 0.7) continue
                if (!t.hasSpire && d.next() >= MegacityParams.BEACON_CHANCE) continue
                if (!inSight(tops, cols, layer, rx - 2, rx + 2, ry - 2)) continue
                val period = d.between(MegacityParams.BEACON_PERIOD)
                beacons += Beacon(rx, ry - 1, layer, period, d.next())
            }
        }
        return beacons
    }

    /** Where the rooftop ledge is: across the bottom left (lower on a wide screen), its railing on it. */
    fun placeLedge(w: Double, h: Double): Ledge {
        val p = MegacityParams
        val tall = w < h
        val top = (if (tall) p.TALL_LEDGE_TOP else p.WIDE_LEDGE_TOP) * h
        val right = if (tall) p.TALL_LEDGE_RIGHT_W * w + p.TALL_LEDGE_RIGHT_H * h else p.WIDE_LEDGE_RIGHT_W * w + p.WIDE_LEDGE_RIGHT_H * h
        return Ledge(top, right, top - (if (tall) p.TALL_RAIL else p.WIDE_RAIL) * h)
    }

    /** Lays out the city for a w × h dp screen. */
    fun build(w: Double, h: Double, rng: Rng): City {
        val d = Draws(rng)
        val cols = ceil(w).toInt() + 1
        val layers = MegacityParams.LAYERS.indices.map { towersFor(it, w, h, d) }
        val tops = layers.map { columnTops(it, cols) }
        val signs = placeSigns(w, h, cols, layers, tops, d)
        val beacons = placeBeacons(h, cols, layers, tops, d)
        return City(w, h, cols, layers, tops, signs, beacons, placeLedge(w, h))
    }

    /**
     * The skyline texture (RGBA float, SKYLINE_WIDTH across, city.rows rows
     * per layer): per column the top there, the tower's left edge and width,
     * its seed (negative on a spire). NO_TOWER where there's none.
     */
    fun skyline(city: City): FloatArray {
        val rows = city.rows
        val data = FloatArray(SKYLINE_WIDTH * rows * city.layers.size * 4)
        fun at(l: Int, c: Int) = ((l * rows + c / SKYLINE_WIDTH) * SKYLINE_WIDTH + c % SKYLINE_WIDTH) * 4
        for (l in city.layers.indices) {
            for (c in 0 until city.cols) data[at(l, c)] = NO_TOWER
            for (t in city.layers[l]) {
                fun put(from: Int, width: Int, top: Int, seed: Int) {
                    for (c in max(0, from) until min(city.cols, from + width)) {
                        val i = at(l, c)
                        if (top > data[i]) continue
                        data[i] = top.toFloat()
                        data[i + 1] = t.x.toFloat()
                        data[i + 2] = t.w.toFloat()
                        data[i + 3] = seed.toFloat()
                    }
                }
                put(t.x, t.w, t.top, t.seed)
                t.upper?.let { put(it.x, it.w, it.top, t.seed) }
                if (t.hasSpire) put(t.spireX, 1, t.spireTop, -t.seed)
            }
        }
        return data
    }

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
    private fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t }
    private val WHITE = floatArrayOf(1f, 1f, 1f)

    /** The city's colours from a palette (city.ts cityTones): with NEON, the reference's. */
    fun tones(p: PaletteColors): CityTones {
        val bg = rgb(p.bg)
        val cool = rgb(p.cool)
        val accent = rgb(p.accent)
        val bright = rgb(p.textBright)
        return CityTones(
            skyTop = cool,
            skyMid = mix(mix(cool, accent, 0.18f), bg, 0.08f),
            skyLow = mix(cool, accent, 0.55f),
            lowGlow = mix(bg, accent, 0.24f),
            faceFar = mix(cool, bg, 0.2f),
            faceNear = mix(cool, bg, 0.6f),
            edge = mix(cool, WHITE, 0.55f),
            winPale = mix(cool, WHITE, 0.55f),
            winViolet = mix(mix(cool, accent, 0.5f), WHITE, 0.3f),
            winPink = mix(accent, WHITE, 0.3f),
            winWhite = mix(cool, WHITE, 0.82f),
            signWarm = accent,
            signCool = mix(cool, WHITE, 0.45f),
            beacon = accent,
            droneWhite = mix(bright, WHITE, 0.5f),
            droneCool = mix(cool, WHITE, 0.5f),
            seam = mix(bg, cool, 0.16f),
            ledge = mix(bg, cool, 0.16f),
            ledgeTop = mix(mix(cool, WHITE, 0.15f), bg, 0.3f),
            rail = mix(bg, cool, 0.3f),
            glass = mix(bg, cool, 0.45f),
        )
    }

    /** The haze's colour at height y on a screen h tall, into [out] at [o] (as mc_city.frag works it out). */
    fun hazeAt(t: CityTones, y: Float, h: Float, out: FloatArray, o: Int) {
        val k = (y / h).coerceIn(0f, 1f)
        val a = if (k < 0.5f) t.skyTop else t.skyMid
        val b = if (k < 0.5f) t.skyMid else t.skyLow
        val f = if (k < 0.5f) k * 2 else k * 2 - 1
        val s = ((k - 0.4f) / 0.6f).coerceIn(0f, 1f)
        val glow = s * s * (3 - 2 * s)
        for (i in 0 until 3) out[o + i] = a[i] + (b[i] - a[i]) * f + t.lowGlow[i] * glow
    }

    /**
     * The foreground as triangles (SHAPE floats a vertex): the glass railing
     * (tinted glass, a top rail, posts), then the ledge with the light along
     * its edge, a band and seams on its face.
     */
    fun foreground(city: City, t: CityTones): FloatArray {
        val out = ArrayList<Float>()
        fun vertex(x: Double, y: Double, c: FloatArray, a: Float) {
            out += x.toFloat(); out += y.toFloat(); out += c[0] * a; out += c[1] * a; out += c[2] * a; out += a
        }
        fun rect(x0: Double, y0: Double, x1: Double, y1: Double, c: FloatArray, a: Float) {
            vertex(x0, y0, c, a); vertex(x1, y0, c, a); vertex(x0, y1, c, a)
            vertex(x1, y0, c, a); vertex(x1, y1, c, a); vertex(x0, y1, c, a)
        }
        val l = city.ledge
        val w = city.w
        val h = city.h
        val railEnd = l.right - 3
        rect(-2.0, l.railTop, railEnd, l.top, t.glass, 0.3f)
        rect(-2.0, l.railTop, railEnd, l.railTop + 2.2, t.rail, 1f)
        for (x in doubleArrayOf(w * 0.02, (w * 0.02 + railEnd) / 2, railEnd - 3)) rect(x, l.railTop, x + 3, l.top, t.rail, 1f)
        rect(-2.0, l.top, l.right, h + 2, t.ledge, 1f)
        rect(-2.0, l.top, l.right, l.top + 1.6, t.ledgeTop, 0.85f)
        rect(-2.0, l.top + h * 0.03, l.right, l.top + h * 0.042, t.seam, 0.6f)
        for (k in doubleArrayOf(0.22, 0.51, 0.8)) rect(l.right * k, l.top + h * 0.042, l.right * k + 1, h + 2, t.seam, 0.5f)
        return out.toFloatArray()
    }
}
