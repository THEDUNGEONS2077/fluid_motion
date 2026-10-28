/*
 * lib/prism/Prism.kt: PRISM's scene and motion, a port of cyberdeck_terminal's
 * (src/wallpaper/prism/: params.ts, field.ts, engine.ts prismTones), rule for
 * rule: the glass blocks, the light pools among them, the camera (swaying),
 * the blocks' order back to front, and each frame's data for the shaders.
 * Motion keeps its own phases (the sway's, each light's breath), advanced
 * by each frame's step and wrapped (PrismMotion): no clock goes into a sine,
 * so it moves as smoothly after months as on day one (the web uses its
 * clock: a page is reopened often).
 *
 * Layer: lib (pure Kotlin). The renderer (scene/prism/PrismRenderer.kt) draws
 * it with the web's own shaders (pr_*, byte for byte) at HIGH and ULTRA, and
 * at LOW and MEDIUM with lighter ones of the phone's own (prism_lite.*: the
 * light pools summed at the corners of a fine grid on each face, not at
 * every pixel; gridCubeVertices); the JVM frame dump
 * (FrameDumpTest) packs frames the same way, so tools/shader-check draws what
 * the phone does. Nothing here allocates per frame: the camera, the order
 * and the packed arrays are made once and written in place.
 *
 * World units: a cell is 1 unit; y is up; the field is centred on the origin.
 * PHONE (PrismView): an upright screen would see a thin slice through the
 * web's lens, so its view is widened: as wide as the web's 16:9 sees,
 * narrowed by √(how much narrower the screen is) (SUNSET's rule), and the
 * light pools are spread over that narrower view.
 */
package com.thedungeons2077.fluidmotion.lib.prism

import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.Quality
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import com.thedungeons2077.fluidmotion.lib.rain.Span
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** PRISM's numbers: the web's (params.ts), plus the few a phone needs, marked PHONE. */
object PrismParams {
    /** The field: cells across and deep (units), and the layers blocks sit on (their floors, units up). */
    const val CELLS = 30
    val LAYERS = floatArrayOf(0f, 1.15f, 2.5f)

    /** Blocks: footprint (cells, whole ranges), height (units), how full each layer is, the seam between neighbours (units). */
    val WIDTH = intArrayOf(1, 3)
    val DEPTH = intArrayOf(1, 3)
    val HEIGHT = Span(0.5f, 2.2f)
    val FILL = floatArrayOf(0.9f, 0.42f, 0.2f)
    const val SEAM = 0.035f

    /** The camera: target (units), yaw and pitch (degrees), distance (units), vertical field of view on 16:9 (degrees). */
    val TARGET = floatArrayOf(0f, 0.6f, 0f)
    const val YAW = 36f
    const val PITCH = 38f
    const val DISTANCE = 12.5f
    const val FOV = 34f

    /** The camera's sway: how far (degrees) and how slowly (seconds a cycle). */
    const val SWAY_YAW = 4f
    const val SWAY_YAW_PERIOD = 53f
    const val SWAY_PITCH = 1.6f
    const val SWAY_PITCH_PERIOD = 67f

    /** The glass: its body's opacity and how much brighter at grazing angles. */
    const val GLASS_FRONT = 0.8f
    const val FRESNEL = 0.3f

    /** The light pools: how many magenta and blue, their reach (units) and strength. */
    const val MAGENTA = 6
    const val BLUE = 4
    val REACH = Span(1.3f, 2.8f)
    val STRENGTH = Span(0.9f, 1.8f)

    /** How the lights breathe: how much, and how slowly (seconds a cycle). */
    const val BREATHE = 0.22f
    val BREATHE_PERIOD = Span(9f, 17f)

    /** Half the width of the field's part in view on 16:9 (units): where the light pools go. */
    const val VIEW = 6f

    /** The edges: core width and glow reach (dp, the web's CSS px). */
    const val EDGE_WIDTH = 1f
    const val EDGE_GLOW = 2.4f

    /** How strongly a light pool tints the glass, and the most the shader takes. */
    const val LIGHT_GAIN = 0.2f
    const val MAX_LIGHTS = 16

    /**
     * PHONE: the lighter shaders (prism_lite.*, LOW and MEDIUM, for slower
     * phones) sum the light pools at the corners of a LITE_GRID × LITE_GRID
     * grid on each face and blend between them, instead of at every pixel:
     * the pools are most of PRISM's cost (with none, a frame took a third
     * of the time, 0.11.0's software check). The pools are wide and soft
     * (reach 1.3 units and up, a face 3 at most), so at 6 × 6 the frame
     * came out within a step of colour on all but 0.4% of pixels (at most
     * 18 of 255, along a few edges), for 2.2 × less work; 4 × 4 left 1.2%
     * off. A cell is at most half a unit across.
     */
    const val LITE_GRID = 6

    /** PHONE: whether a quality draws with the lighter shaders (LOW and MEDIUM); HIGH and ULTRA keep the web's exactly. */
    fun lite(quality: Quality): Boolean = quality == Quality.LOW || quality == Quality.MEDIUM

    /** PHONE: the screen shape the web's view was made for (16:9), and how much of the lost width an upright screen gets back (√). */
    const val REFERENCE_ASPECT = 16f / 9f
    const val NARROW_POWER = 0.5f

    /** Per glow level: the edges' glow and the lights' strength. */
    class Glow(val edge: Float, val light: Float)

    val GLOW: Map<GlowLevel, Glow> = mapOf(
        GlowLevel.LOW to Glow(0.25f, 0.85f),
        GlowLevel.MEDIUM to Glow(0.4f, 0.95f),
        GlowLevel.HIGH to Glow(0.55f, 1f),
        GlowLevel.MAX to Glow(0.75f, 1.1f),
    )

    /** The longest step the motion takes in one frame (s). */
    const val MAX_FRAME_STEP = 0.1f
}

/** Floats per block: min corner (x, y, z), size (w, h, d). */
const val BOX = 6

/** A light pool: where (units), colour (0 magenta, 1 blue), reach (units), strength, and how it breathes (period s, starting phase). */
class Light(val x: Float, val y: Float, val z: Float, val kind: Int, val reach: Float, val strength: Float, val period: Float, val phase: Float)

/** One turn (radians). */
private const val TAU = (2 * PI).toFloat()

/** The motion's phases (radians, each kept within a turn): the camera's sway and each light's breath. */
class PrismMotion(field: PrismField) {
    var yaw = 0f
        private set
    var pitch = 0f
        private set
    private val periods = FloatArray(field.lights.size) { field.lights[it].period }

    /** Each light's breath phase. */
    val breath = FloatArray(field.lights.size) { field.lights[it].phase }

    /** Moves on by [dt] seconds (capped, so a pause doesn't jump it). */
    fun advance(dt: Float) {
        val step = min(dt, PrismParams.MAX_FRAME_STEP)
        yaw = (yaw + step * TAU / PrismParams.SWAY_YAW_PERIOD) % TAU
        pitch = (pitch + step * TAU / PrismParams.SWAY_PITCH_PERIOD) % TAU
        for (i in breath.indices) breath[i] = (breath[i] + step * TAU / periods[i]) % TAU
    }

    /** Light [i]'s brightness multiplier now. */
    fun breathOf(i: Int): Float = 1f + PrismParams.BREATHE * sin(breath[i])
}

/** The scene: the blocks (BOX floats each) and the light pools. */
class PrismField(val boxes: FloatArray, val count: Int, val lights: List<Light>)

/** A whole number in [r[0], r[1]]. */
private fun Rng.whole(r: IntArray): Int = r[0] + floor(next() * (r[1] - r[0] + 1)).toInt()

/**
 * Builds the field from [rng], as the web's buildField: blocks layer by
 * layer, never over a cell whose blocks reach above the layer's floor (so
 * none cut through each other); then the light pools, one per patch of a
 * 3 × 3 grid over the part in view (half-width [view]), magenta first.
 */
fun buildField(rng: Rng, view: Float = PrismParams.VIEW): PrismField {
    val p = PrismParams
    val n = p.CELLS
    val half = n / 2f
    val top = FloatArray(n * n)
    val boxes = ArrayList<Float>()
    for (layer in p.LAYERS.indices) {
        val floor = p.LAYERS[layer]
        val last = layer == p.LAYERS.size - 1
        val taken = BooleanArray(n * n)
        val want = (p.FILL[layer] * n * n).roundToInt()
        var covered = 0
        var tries = 0
        while (tries < n * n * 6 && covered < want) {
            tries++
            val w = rng.whole(p.WIDTH)
            val d = rng.whole(p.DEPTH)
            val cx = floor(rng.next() * (n - w + 1)).toInt()
            val cz = floor(rng.next() * (n - d + 1)).toInt()
            var free = true
            loop@ for (i in cx until cx + w) for (k in cz until cz + d) {
                if (taken[k * n + i] || top[k * n + i] > floor - 0.05f) {
                    free = false
                    break@loop
                }
            }
            if (!free) continue
            // Below a higher layer, a block stays short enough to slip under it.
            val tall = rng.between(p.HEIGHT)
            val h = if (last) tall else min(tall, max(p.HEIGHT.start, tall * 0.6f))
            for (i in cx until cx + w) for (k in cz until cz + d) {
                taken[k * n + i] = true
                top[k * n + i] = max(top[k * n + i], floor + h)
            }
            covered += w * d
            val s = p.SEAM
            boxes.addAll(listOf(cx - half + s / 2, floor, cz - half + s / 2, w - s, h, d - s))
        }
    }
    val lights = ArrayList<Light>()
    // The patches in a random order (a Fisher–Yates shuffle, as the web's).
    val patches = IntArray(9) { it }
    for (i in patches.size - 1 downTo 1) {
        val r = floor(rng.next() * (i + 1)).toInt()
        val t = patches[i]
        patches[i] = patches[r]
        patches[r] = t
    }
    val cell = 2f * view / 3f
    for (i in 0 until p.MAGENTA + p.BLUE) {
        val patch = patches[i % 9]
        val x = -view + ((patch % 3) + rng.next()) * cell
        val z = -view + ((patch / 3) + rng.next()) * cell
        val y = rng.between(Span(0.3f, 2.6f))
        val reach = rng.between(p.REACH)
        lights += Light(x, y, z, if (i < p.MAGENTA) 0 else 1, reach, rng.between(p.STRENGTH), rng.between(p.BREATHE_PERIOD), rng.next() * 2f * PI.toFloat())
    }
    return PrismField(boxes.toFloatArray(), boxes.size / BOX, lights)
}

/**
 * How a screen frames PRISM: its aspect and the vertical field of view
 * (radians, zoomed). On 16:9 the web's own; PHONE: an upright screen sees
 * √(how much narrower) less across than the web's 16:9, not the full ratio
 * less (which left a slice a few blocks wide), so its view is taller; and
 * the light pools spread over the narrower part in view.
 */
class PrismView(val width: Float, val height: Float, val pxPerDp: Float, val zoom: Float) {
    private val p = PrismParams

    /** Width over height. */
    val aspect: Float = width / height

    /** How much narrower than 16:9 the screen is (1 at 16:9 or wider). */
    val narrow: Float = max(1f, p.REFERENCE_ASPECT / aspect)

    /** The vertical field of view (radians), zoomed in by the zoom (a narrower view, as the web's). */
    val fov: Float

    /** Half the width of the part in view the light pools spread over (units). */
    val lightView: Float = p.VIEW / Math.pow(narrow.toDouble(), p.NARROW_POWER.toDouble()).toFloat()

    init {
        val webHalfV = tan(p.FOV / 2f * PI.toFloat() / 180f)
        // As much across as the web's 16:9 sees, over √narrow; then as tall as that makes it (never less than the web's).
        val halfAcross = webHalfV * p.REFERENCE_ASPECT / Math.pow(narrow.toDouble(), p.NARROW_POWER.toDouble()).toFloat()
        val halfV = max(webHalfV, halfAcross / aspect)
        fov = 2f * atan(halfV) / (if (zoom > 0f) zoom else 1f)
    }
}

/** The camera: where it is, which way it looks, and its view-projection matrix (column-major), written in place by [update]. */
class PrismCamera {
    val eye = FloatArray(3)
    val forward = FloatArray(3)
    val viewProj = FloatArray(16)
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val r = FloatArray(3)
    private val u = FloatArray(3)

    /** The camera with the sway at [motion]'s phases, for a screen of [aspect] and a vertical field of view [fov] (radians). */
    fun update(motion: PrismMotion, aspect: Float, fov: Float) {
        val p = PrismParams
        val rad = PI.toFloat() / 180f
        val yaw = (p.YAW + p.SWAY_YAW * sin(motion.yaw)) * rad
        val pitch = (p.PITCH + p.SWAY_PITCH * sin(motion.pitch)) * rad
        val tx = p.TARGET[0]
        val ty = p.TARGET[1]
        val tz = p.TARGET[2]
        eye[0] = tx + p.DISTANCE * cos(pitch) * sin(yaw)
        eye[1] = ty + p.DISTANCE * sin(pitch)
        eye[2] = tz + p.DISTANCE * cos(pitch) * cos(yaw)
        forward[0] = tx - eye[0]
        forward[1] = ty - eye[1]
        forward[2] = tz - eye[2]
        normalize(forward)
        // Right = forward × up; up' = right × forward.
        r[0] = -forward[2]
        r[1] = 0f
        r[2] = forward[0]
        normalize(r)
        u[0] = r[1] * forward[2] - r[2] * forward[1]
        u[1] = r[2] * forward[0] - r[0] * forward[2]
        u[2] = r[0] * forward[1] - r[1] * forward[0]
        view[0] = r[0]; view[1] = u[0]; view[2] = -forward[0]; view[3] = 0f
        view[4] = r[1]; view[5] = u[1]; view[6] = -forward[1]; view[7] = 0f
        view[8] = r[2]; view[9] = u[2]; view[10] = -forward[2]; view[11] = 0f
        view[12] = -dot(r, eye); view[13] = -dot(u, eye); view[14] = dot(forward, eye); view[15] = 1f
        val k = 1f / tan(fov / 2f)
        val near = 0.5f
        val far = 80f
        proj.fill(0f)
        proj[0] = k / aspect
        proj[5] = k
        proj[10] = (far + near) / (near - far)
        proj[11] = -1f
        proj[14] = 2f * far * near / (near - far)
        for (c in 0 until 4) for (row in 0 until 4) {
            viewProj[c * 4 + row] = proj[row] * view[c * 4] + proj[4 + row] * view[c * 4 + 1] + proj[8 + row] * view[c * 4 + 2] + proj[12 + row] * view[c * 4 + 3]
        }
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun normalize(a: FloatArray) {
        // A plain if, not takeIf: that boxes the Float (garbage every frame).
        val len = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val l = if (len > 0f) len else 1f
        a[0] /= l
        a[1] /= l
        a[2] /= l
    }
}

/**
 * Each frame's data, packed for the shaders in arrays made once: the blocks
 * in order back to front (an insertion sort into [order], kept between
 * frames: the camera only sways, so it's nearly sorted already), and the
 * light pools' uniforms. As the web's sortBackToFront and its frame loop.
 */
class PrismFrame(private val field: PrismField) {
    /** The blocks' order, farthest first. */
    val order = IntArray(field.count) { it }
    private val depth = FloatArray(field.count)

    /** The blocks in that order, BOX floats each (the instance buffer). */
    val sorted = FloatArray(field.count * BOX)

    /** The lights: (x, y, z, reach) and colour × strength × breath, up to MAX_LIGHTS. */
    val lightPos = FloatArray(PrismParams.MAX_LIGHTS * 4)
    val lightCol = FloatArray(PrismParams.MAX_LIGHTS * 3)
    var lights = 0
        private set

    /** Packs the frame for [cam] with the lights breathing as [motion] says, in [tones], at the glow level's light strength [light]. */
    fun pack(cam: PrismCamera, motion: PrismMotion, tones: PrismTones, light: Float) {
        val b = field.boxes
        val ex = cam.eye[0]
        val ey = cam.eye[1]
        val ez = cam.eye[2]
        val fx = cam.forward[0]
        val fy = cam.forward[1]
        val fz = cam.forward[2]
        for (i in 0 until field.count) {
            val o = i * BOX
            depth[i] = (b[o] + b[o + 3] / 2f - ex) * fx + (b[o + 1] + b[o + 4] / 2f - ey) * fy + (b[o + 2] + b[o + 5] / 2f - ez) * fz
        }
        // Farthest first.
        for (i in 1 until field.count) {
            val v = order[i]
            val dv = depth[v]
            var j = i - 1
            while (j >= 0 && depth[order[j]] < dv) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = v
        }
        for (i in 0 until field.count) System.arraycopy(b, order[i] * BOX, sorted, i * BOX, BOX)
        lights = min(PrismParams.MAX_LIGHTS, field.lights.size)
        for (i in 0 until lights) {
            val l = field.lights[i]
            val c = if (l.kind == 0) tones.magenta else tones.blue
            val k = l.strength * motion.breathOf(i) * light * PrismParams.LIGHT_GAIN
            lightPos[i * 4] = l.x
            lightPos[i * 4 + 1] = l.y
            lightPos[i * 4 + 2] = l.z
            lightPos[i * 4 + 3] = l.reach
            lightCol[i * 3] = c[0] * k
            lightCol[i * 3 + 1] = c[1] * k
            lightCol[i * 3 + 2] = c[2] * k
        }
    }

    /** A block's depth along the camera's view (for tests). */
    fun depthOf(i: Int): Float = depth[i]
}

/** PRISM's colours from a palette: everything from its --bg, --accent and --cool (the web's prismTones). */
class PrismTones(colors: PaletteColors) {
    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
    private fun mix(a: FloatArray, b: FloatArray, k: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * k }
    private val bg = rgb(colors.bg)
    private val cool = rgb(colors.cool)

    /** The two light colours: magenta (--accent), and blue (--cool) at about half: the magenta leads. */
    val magenta = rgb(colors.accent)
    val blue = FloatArray(3) { cool[it] * 0.55f }

    /** The deep navy behind and in the glass. */
    val navy = mix(mix(bg, cool, 0.12f), magenta, 0.035f)

    /** The edges: a dim, cool glint. */
    val edge = mix(cool, floatArrayOf(1f, 1f, 1f), 0.3f).let { e -> FloatArray(3) { e[it] * 0.45f } }
}

/**
 * A unit cube's 36 vertices: corner (0–1) and outward normal, 6 floats each,
 * as the web's cubeVertices: the faces facing away from the camera's corner
 * (−x, −y, −z) first, so within each block its far faces draw before its
 * near ones; each triangle wound counter-clockwise seen from outside.
 */
fun cubeVertices(): FloatArray {
    val faces = arrayOf(intArrayOf(-1, 0, 0), intArrayOf(0, -1, 0), intArrayOf(0, 0, -1), intArrayOf(1, 0, 0), intArrayOf(0, 1, 0), intArrayOf(0, 0, 1))
    val out = FloatArray(36 * 6)
    var o = 0
    for (n in faces) {
        val axis = n.indexOfFirst { it != 0 }
        val side = if (n[axis] > 0) 1f else 0f
        val (u, v) = (0..2).filter { it != axis }
        fun corner(a: Float, b: Float) = FloatArray(3).also {
            it[axis] = side
            it[u] = a
            it[v] = b
        }
        var quad = arrayOf(corner(0f, 0f), corner(1f, 0f), corner(1f, 1f), corner(0f, 1f))
        // Flip the order if it winds the wrong way round this normal.
        val e1 = FloatArray(3) { quad[1][it] - quad[0][it] }
        val e2 = FloatArray(3) { quad[2][it] - quad[0][it] }
        val cx = e1[1] * e2[2] - e1[2] * e2[1]
        val cy = e1[2] * e2[0] - e1[0] * e2[2]
        val cz = e1[0] * e2[1] - e1[1] * e2[0]
        if (cx * n[0] + cy * n[1] + cz * n[2] < 0) quad = arrayOf(quad[0], quad[3], quad[2], quad[1])
        for (i in intArrayOf(0, 1, 2, 0, 2, 3)) {
            for (c in 0 until 3) out[o++] = quad[i][c]
            for (c in 0 until 3) out[o++] = n[c].toFloat()
        }
    }
    return out
}

/**
 * PHONE: the unit cube for the lighter shaders: [n] × [n] quads a face (each
 * two triangles), corner and normal, 6 floats a vertex; the faces in
 * cubeVertices' order and each wound as its face is there (counter-clockwise
 * from outside), so it draws the same, with corners for the light pools'
 * sum. At n = 1 it is cubeVertices exactly.
 */
fun gridCubeVertices(n: Int): FloatArray {
    val cube = cubeVertices()
    val out = FloatArray(6 * n * n * 6 * 6)
    var o = 0
    for (f in 0 until 6) {
        // The face's quad: cubeVertices writes it as corners (0, 1, 2) and (0, 2, 3).
        val base = f * 6 * 6
        fun corner(v: Int, a: Int) = cube[base + v * 6 + a]
        val c0 = 0
        val c1 = 1
        val c2 = 2
        val c3 = 5
        for (i in 0 until n) for (j in 0 until n) {
            // Each grid corner (u along c0→c1, w along c0→c3): the face is a flat square, so this is exact.
            val us = floatArrayOf(i / n.toFloat(), (i + 1) / n.toFloat(), (i + 1) / n.toFloat(), i / n.toFloat(), (i + 1) / n.toFloat(), i / n.toFloat())
            val ws = floatArrayOf(j / n.toFloat(), j / n.toFloat(), (j + 1) / n.toFloat(), j / n.toFloat(), (j + 1) / n.toFloat(), (j + 1) / n.toFloat())
            for (k in 0 until 6) {
                for (a in 0 until 3) out[o + a] = corner(c0, a) + (corner(c1, a) - corner(c0, a)) * us[k] + (corner(c3, a) - corner(c0, a)) * ws[k] + (corner(c2, a) - corner(c1, a) - corner(c3, a) + corner(c0, a)) * us[k] * ws[k]
                for (a in 3 until 6) out[o + a] = corner(c0, a)
                o += 6
            }
        }
    }
    return out
}
