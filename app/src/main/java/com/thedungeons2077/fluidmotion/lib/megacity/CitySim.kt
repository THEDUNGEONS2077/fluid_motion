/*
 * lib/megacity/CitySim.kt: what moves in MEGACITY, and how each frame's
 * lights are packed for the GPU. A port of cyberdeck_terminal's
 * src/wallpaper/megacity/sim.ts.
 *
 * Layer: lib (pure Kotlin, unit tested). The city itself never changes
 * after it's laid out: its windows go on and off in the shader, by the
 * clock kept here. The signs breathe and now and then flicker like failing
 * tubes, the beacons blink, drones cross trailing short tails, patches of
 * haze drift. Pools and arrays are made once, colours worked out into the
 * caller's own room: a frame allocates nothing.
 */
package com.thedungeons2077.fluidmotion.lib.megacity

import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** MEGACITY's motion on [city]. */
class CitySim(val city: City, private val rng: Rng) {
    private val p = MegacityParams

    /** The city's clock (s), wrapped at TIME_WRAP (the shader's windows run on it). */
    var time = 0f
        private set

    /** Each sign's brightness now (0–1), and its flicker: seconds until the next, seconds left of this one. */
    val signBright = FloatArray(city.signs.size) { 1f }
    private val flickerIn = FloatArray(city.signs.size) { between(p.FLICKER_EVERY) }
    private val flickerLeft = FloatArray(city.signs.size)

    /** Drones: place, speed (dp a second, + to the right), tail and size (dp), colour (0 white, 1 cool, 2 accent). */
    val dx = FloatArray(p.DRONES_MAX)
    val dy = FloatArray(p.DRONES_MAX)
    val dv = FloatArray(p.DRONES_MAX)
    val dTail = FloatArray(p.DRONES_MAX)
    val dSize = FloatArray(p.DRONES_MAX)
    val dKind = IntArray(p.DRONES_MAX)
    var drones = 0
        private set
    private val droneTarget = min(p.DRONES_MAX, max(p.DRONES_MIN, Math.round(city.w / p.DRONES_PER).toInt()))

    /** Haze patches: place, size, drift, strength. */
    val hx = FloatArray(p.HAZE_COUNT)
    val hy = FloatArray(p.HAZE_COUNT)
    val hrx = FloatArray(p.HAZE_COUNT)
    val hry = FloatArray(p.HAZE_COUNT)
    private val hv = FloatArray(p.HAZE_COUNT)
    val ha = FloatArray(p.HAZE_COUNT)

    private fun between(r: Range): Float = (r.lo + (r.hi - r.lo) * rng.next()).toFloat()

    init {
        val w = city.w.toFloat()
        val h = city.h.toFloat()
        for (i in 0 until p.HAZE_COUNT) {
            hrx[i] = between(p.HAZE_RX) * w
            hry[i] = between(p.HAZE_RY) * h
            hx[i] = rng.next() * w
            hy[i] = between(p.HAZE_Y) * h
            hv[i] = between(p.HAZE_SPEED) * if (rng.next() < 0.5f) -1f else 1f
            ha[i] = between(p.HAZE_ALPHA)
        }
        // Open with the drones already on their way.
        while (drones < droneTarget) launch(true)
    }

    /** A new drone from one edge (or, at the start, anywhere). */
    private fun launch(anywhere: Boolean) {
        val i = drones++
        val right = rng.next() < 0.5f
        dv[i] = between(p.DRONE_SPEED) * if (right) 1f else -1f
        dTail[i] = between(p.DRONE_TAIL)
        dSize[i] = between(p.DRONE_SIZE)
        val w = city.w.toFloat()
        dx[i] = if (anywhere) rng.next() * w else if (right) -dTail[i] else w + dTail[i]
        dy[i] = between(p.DRONE_Y) * city.h.toFloat()
        dKind[i] = if (rng.next() < 0.6f) 0 else if (rng.next() < 0.6f) 1 else 2
    }

    /** Moves everything on by [dt] seconds. */
    fun advance(dt: Float) {
        val w = city.w.toFloat()
        time = (time + dt) % p.TIME_WRAP
        // Signs: a slow breath; now and then a flicker, the tube stuttering on and off.
        for (i in city.signs.indices) {
            if (flickerLeft[i] > 0f) {
                flickerLeft[i] -= dt
                val on = floor(time * p.FLICKER_RATE + i * 7.3f).toInt() % 3 != 0
                signBright[i] = if (on) 0.95f else 0.12f
            } else {
                flickerIn[i] -= dt
                if (flickerIn[i] <= 0f) {
                    flickerIn[i] = between(p.FLICKER_EVERY)
                    flickerLeft[i] = between(p.FLICKER_LENGTH)
                }
                signBright[i] = 0.88f + 0.12f * sin(time * 0.6f + city.signs[i].phase.toFloat())
            }
        }
        // Drones: on across; one gone off the far side, the last takes its place, and a new one sets off.
        var i = 0
        while (i < drones) {
            dx[i] += dv[i] * dt
            val gone = if (dv[i] > 0f) dx[i] - dTail[i] > w else dx[i] + dTail[i] < 0f
            if (!gone) {
                i++
                continue
            }
            drones--
            dx[i] = dx[drones]; dy[i] = dy[drones]; dv[i] = dv[drones]
            dTail[i] = dTail[drones]; dSize[i] = dSize[drones]; dKind[i] = dKind[drones]
        }
        while (drones < droneTarget) launch(false)
        // Haze: drifting, round again from the other side.
        for (k in 0 until p.HAZE_COUNT) {
            hx[k] += hv[k] * dt
            if (hx[k] > w + hrx[k]) hx[k] = -hrx[k] else if (hx[k] < -hrx[k]) hx[k] = w + hrx[k]
        }
    }

    /** How bright beacon [b] is now (0–1): a short blink once a period. */
    fun beacon(b: Int): Float {
        val beacon = city.beacons[b]
        val period = beacon.period.toFloat()
        val x = (time + beacon.phase.toFloat() * period) % period
        return if (x < p.BEACON_ON) sin(PI.toFloat() * x / p.BEACON_ON) else 0f
    }
}

/** MEGACITY's packing: each frame's signs and glows, into arrays made once. */
object CityFrame {
    /** Floats per instance: a sign (rect; colour, brightness; seed, upright, halo, 0), a glow (x, y, rx, ry; colour, strength; core, tail, kind, 0). */
    const val SIGN = 12
    const val GLOW = 12

    /** Room for a city's signs and glows (floats). */
    fun signRoom(city: City) = max(1, city.signs.size) * SIGN
    fun glowRoom(city: City) = (MegacityParams.HAZE_COUNT + city.beacons.size + MegacityParams.DRONES_MAX) * GLOW

    /** A colour softened by the haze at a layer (its fog, times [share]), into [out] at [o]; [scratch] is 3 floats of room. */
    private fun fogged(out: FloatArray, o: Int, c: FloatArray, t: CityTones, layer: Int, y: Float, h: Float, share: Float, scratch: FloatArray) {
        CityLayout.hazeAt(t, y, h, scratch, 0)
        val f = MegacityParams.LAYERS[layer].fog * share
        for (k in 0 until 3) out[o + k] = c[k] + (scratch[k] - c[k]) * f
    }

    /** This frame's signs into [out]: returns how many. [scratch] is the caller's 3 floats (each scene its own). */
    fun signs(sim: CitySim, t: CityTones, glow: GlowLevel, out: FloatArray, scratch: FloatArray): Int {
        val city = sim.city
        val g = MegacityParams.GLOW.getValue(glow)
        var n = 0
        for (i in city.signs.indices) {
            if ((n + 1) * SIGN > out.size) break
            val s = city.signs[i]
            val o = n++ * SIGN
            out[o] = s.x.toFloat(); out[o + 1] = s.y.toFloat(); out[o + 2] = s.w.toFloat(); out[o + 3] = s.h.toFloat()
            fogged(out, o + 4, if (s.cool) t.signCool else t.signWarm, t, s.layer, s.y.toFloat(), city.h.toFloat(), 0.6f, scratch)
            out[o + 7] = sim.signBright[i]
            out[o + 8] = s.seed.toFloat(); out[o + 9] = if (s.vertical) 1f else 0f
            out[o + 10] = g.sign * if (s.layer == 1) 0.5f else 1f; out[o + 11] = 0f
        }
        return n
    }

    /** Writes a glow's place, size, strength and shape at [o] of [out] (its colour is written after). */
    private fun glowAt(out: FloatArray, o: Int, x: Float, y: Float, rx: Float, ry: Float, a: Float, core: Float, tail: Float, kind: Float) {
        out[o] = x; out[o + 1] = y; out[o + 2] = rx; out[o + 3] = ry
        out[o + 7] = a; out[o + 8] = core; out[o + 9] = tail; out[o + 10] = kind; out[o + 11] = 0f
    }

    /** This frame's glows into [out]: the haze patches, the beacons, the drones. Returns how many. [scratch] is the caller's 3 floats. */
    fun glows(sim: CitySim, t: CityTones, glow: GlowLevel, out: FloatArray, scratch: FloatArray): Int {
        val city = sim.city
        val g = MegacityParams.GLOW.getValue(glow)
        val h = city.h.toFloat()
        val room = out.size / GLOW
        var n = 0
        for (k in 0 until MegacityParams.HAZE_COUNT) {
            if (n >= room) return n
            val o = n++ * GLOW
            glowAt(out, o, sim.hx[k], sim.hy[k], sim.hrx[k], sim.hry[k], sim.ha[k], 0f, 0f, 1f)
            // The haze's own colour there, a little lighter.
            CityLayout.hazeAt(t, sim.hy[k], h, out, o + 4)
            for (c in 4 until 7) out[o + c] += (1f - out[o + c]) * 0.25f
        }
        for (b in city.beacons.indices) {
            val a = sim.beacon(b)
            if (a <= 0.01f || n >= room) continue
            val beacon = city.beacons[b]
            val o = n++ * GLOW
            glowAt(out, o, beacon.x.toFloat(), beacon.y.toFloat(), g.light, g.light, a, 1f, 0f, 0f)
            fogged(out, o + 4, t.beacon, t, beacon.layer, beacon.y.toFloat(), h, 0.5f, scratch)
        }
        for (i in 0 until sim.drones) {
            if (n >= room) break
            val c = when (sim.dKind[i]) {
                0 -> t.droneWhite
                1 -> t.droneCool
                else -> t.beacon
            }
            val o = n++ * GLOW
            glowAt(out, o, sim.dx[i], sim.dy[i], g.light, g.light, 0.9f, sim.dSize[i] / 2, sim.dTail[i] * sign(sim.dv[i]), 0f)
            out[o + 4] = c[0]; out[o + 5] = c[1]; out[o + 6] = c[2]
        }
        return n
    }
}
