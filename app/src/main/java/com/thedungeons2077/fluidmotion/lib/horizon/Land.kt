/*
 * lib/horizon/Land.kt: HORIZON's land, made once per screen and zoom: a
 * field of jittered points with heights, cut into triangles; how the
 * screen frames it (HorizonView); its colours; which strips to draw first.
 *
 * Layer: lib (pure Kotlin, no Android or GL; seeded, so tests check it).
 * A port of cyberdeck_terminal's src/wallpaper/horizon/land.ts, in the same
 * order of random draws, with three phone changes:
 *   - the cell and the land's width come from the screen's shape
 *     (HorizonView), not the web's layout height;
 *   - the mesh lists its strips far end first, after the far wall: the
 *     phone draws them back to front with no depth buffer (a buffer the
 *     size of the screen, written and cleared every frame, is a battery
 *     cost the web doesn't count), the GPU dropping each triangle's hidden
 *     side;
 *   - the land is DEPTH (48) units deep, not 64 (HorizonParams).
 *
 * The land is drawn as rows strips (the triangles between a row and the
 * next), each placed by the vertex shader at its distance from the camera,
 * which glides forward: a strip that passes behind the camera comes round
 * at the far end, deep in the haze, so the land never ends and never pops.
 */
package com.thedungeons2077.fluidmotion.lib.horizon

import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.horizon.HorizonParams.CAMERA_HEIGHT
import com.thedungeons2077.fluidmotion.lib.horizon.HorizonParams.DEPTH
import com.thedungeons2077.fluidmotion.lib.horizon.HorizonParams.WALL
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** The land: its rows and points across, its cell (units), each point's place across, depth offset and height (units), row by row. */
class Land(
    val rows: Int,
    val cols: Int,
    val cell: Float,
    val x: FloatArray,
    val dz: FloatArray,
    val y: FloatArray,
    /** For each quad (row, column): true when it's cut corner to corner the other way. */
    val flip: BooleanArray,
) {
    /** Point (row, column)'s index in the arrays. */
    fun at(row: Int, col: Int) = row * cols + col

    /** Vertices in one strip. */
    val stripVertices: Int get() = (cols - 1) * 6

    /** How far the camera goes before the land repeats (units). */
    val length: Float get() = rows * cell

    companion object {
        /** Floats per vertex: x, height (units); strip, row in it (0/1), depth offset (rows; the wall's: units), corner (0, 1, 2; 3: no edges). */
        const val VERTEX = 6

        /** Vertices in the far wall. */
        const val WALL_VERTICES = 6

        /** Points across a land of cells [cell] reaching [halfWidth] either side (odd: one down the middle). */
        fun colsFor(cell: Float, halfWidth: Float): Int = 2 * ceil(halfWidth / cell).toInt() + 1

        /**
         * A new land with cells [cell] units across, reaching [halfWidth]
         * either side. The random numbers are drawn in the web's order (each
         * point's x, depth and height, row by row, then each quad's cut).
         * Depth jitter and heights are in cells, so smaller cells make finer
         * hills of the same character.
         */
        fun build(rng: Rng, cell: Float, halfWidth: Float): Land {
            val p = HorizonParams
            val rows = (DEPTH / cell).roundToInt()
            val cols = colsFor(cell, halfWidth)
            val n = rows * cols
            val x = FloatArray(n)
            val dz = FloatArray(n)
            val y = FloatArray(n)
            val half = (cols - 1) / 2f * cell
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val i = r * cols + c
                    x[i] = -half + c * cell + (rng.next() * 2 - 1) * p.JITTER_X * cell
                    dz[i] = (rng.next() * 2 - 1) * p.JITTER_Z
                    // Most points low; now and then a peak (the reference's spikes), in cells.
                    y[i] = (if (rng.next() < p.PEAK_CHANCE) rng.between(p.HEIGHT_PEAK) else rng.between(p.HEIGHT_LOW)) * cell
                }
            }
            val flip = BooleanArray(rows * (cols - 1)) { rng.next() < 0.5f }
            return Land(rows, cols, cell, x, dz, y, flip)
        }

        /**
         * The land as triangles, three vertices each, not shared (each
         * carries its corner, from which the fragment shader knows how far a
         * pixel is from the edges). First the far wall (strip −1, corner 3:
         * no edges, from below the ground to the horizon), then the strips
         * from the last to the first: drawn in this order from the right
         * place (HorizonDraw), farthest comes first. Every triangle faces the
         * camera counter-clockwise, so the GPU can drop the other side.
         */
        fun mesh(land: Land): FloatArray {
            val rows = land.rows
            val cols = land.cols
            val out = FloatArray((WALL_VERTICES + rows * land.stripVertices) * VERTEX)
            var o = 0
            fun vertex(x: Float, y: Float, strip: Float, inStrip: Float, dz: Float, corner: Float) {
                out[o++] = x
                out[o++] = y
                out[o++] = strip
                out[o++] = inStrip
                out[o++] = dz
                out[o++] = corner
            }
            // The far wall: wider than any screen sees at its depth.
            val w = WALL * 6
            val wall = floatArrayOf(-w, -1f, w, -1f, w, CAMERA_HEIGHT, -w, -1f, w, CAMERA_HEIGHT, -w, CAMERA_HEIGHT)
            for (k in 0 until 6) vertex(wall[k * 2], wall[k * 2 + 1], -1f, 0f, WALL, 3f)
            /** Point (row, col) as row [inStrip] (0 near, 1 far) of strip [s], at triangle corner [corner]. */
            fun put(s: Int, inStrip: Int, col: Int, corner: Int) {
                val i = ((s + inStrip) % rows) * cols + col
                vertex(land.x[i], land.y[i], s.toFloat(), inStrip.toFloat(), land.dz[i], corner.toFloat())
            }
            for (s in rows - 1 downTo 0) {
                for (c in 0 until cols - 1) {
                    // The quad's corners: two near (this row), two far (the next); both triangles counter-clockwise on screen.
                    if (land.flip[s * (cols - 1) + c]) {
                        put(s, 0, c, 0); put(s, 0, c + 1, 1); put(s, 1, c, 2)
                        put(s, 0, c + 1, 0); put(s, 1, c + 1, 1); put(s, 1, c, 2)
                    } else {
                        put(s, 0, c, 0); put(s, 0, c + 1, 1); put(s, 1, c + 1, 2)
                        put(s, 0, c, 0); put(s, 1, c + 1, 1); put(s, 1, c, 2)
                    }
                }
            }
            return out
        }
    }
}

/**
 * How a screen frames HORIZON: everything the renderer and its uniforms
 * need, from the target's size (px), its px per dp and the zoom. The web
 * frames a 16:9 layout; a narrower screen (a phone, upright) keeps the
 * web's camera and lens (the land fills the bottom half, the horizon's
 * glow just above the middle) and makes the cells smaller by the square
 * root of how much narrower it is (HorizonParams.CELL_NARROW_POWER): about
 * half as many across as the web's, so its tall land isn't crowded with
 * hills, passing at the web's pace in cells.
 */
class HorizonView(val width: Float, val height: Float, val pxPerDp: Float, val zoom: Float) {
    private val p = HorizonParams

    /** How much narrower than 16:9 the screen is (1 at 16:9; about 3.85 on an upright S25 Ultra). */
    val narrow: Float = p.REFERENCE_ASPECT / (width / height)

    /** Focal length (px): the web's, a share of the height, zoomed in by the zoom (a narrower view). */
    val focal: Float = p.FOCAL * height * zoom

    /** The horizon (px from the top). */
    val horizon: Float = height * p.HORIZON

    /** The screen's short side (px): the composition's scale (the web's height, on its 16:9). */
    val short: Float = min(width, height)

    /** PHONE: how much bigger the sun is than on the web's 16:9 (1 there; √narrow, about 1.96, on an upright S25 Ultra). */
    val sunScale: Float = max(1f, narrow).pow(p.SUN_NARROW_POWER)

    /** The glow's centre (px from the top-left): just above the horizon. */
    val sunX: Float = width * 0.5f
    val sunY: Float = horizon - short * p.SUN_LIFT * sunScale

    /** How much smaller than the web's the cells are (and the camera's pace): narrow to HorizonParams.CELL_NARROW_POWER. */
    val shrink: Float = narrow.pow(p.CELL_NARROW_POWER)

    /** The cell (units) and the land's half width (units, past the screen's edges at the land's far end). */
    val cell: Float
    val halfWidth: Float

    init {
        // The web's cell at the zoom, smaller on a narrower screen (shrink); zoomed out, grown again to keep within the quad budget.
        var c = (zoom / shrink).coerceIn(p.CELL_MIN, p.CELL_MAX)
        var hw = halfWidthFor(c)
        while ((DEPTH / c).roundToInt().toLong() * (2 * ceil(hw / c).toLong()) > p.QUAD_BUDGET && c < p.CELL_MAX) {
            c = min(p.CELL_MAX, c * 1.08f)
            hw = halfWidthFor(c)
        }
        cell = c
        halfWidth = hw
    }

    /** As far across as the screen sees at the land's far end, and two cells more. */
    private fun halfWidthFor(c: Float) = width / (2f * focal) * DEPTH + 2f * c

    /** How fast the camera glides (units a second): the web's pace in cells, so a cell passes as often as there. */
    val speed: Float = p.SPEED / shrink

    /** The edges' density (px per dp, zoomed). */
    val density: Float get() = pxPerDp
}

/** Which strip to draw from: [nearStrip] is the nearest now; the mesh lists strips far end first, so two draws cover them back to front. */
object HorizonDraw {
    /** The strip the camera is over (the nearest), as the web's draw finds it, for the camera at [camZ] units. */
    fun nearStrip(camZ: Float, land: Land): Int {
        val n = ceil(camZ / land.cell - 1f + 1e-6f).toInt()
        return max(0, n % land.rows)
    }

    /**
     * The first vertex of the strips farther than the nearest one's wrap
     * (strips near−1 down to 0: mesh positions rows−near … rows−1), drawn
     * first; then from 0 (strips rows−1 down to near). Mesh positions count
     * past the wall.
     */
    fun farFirst(near: Int, land: Land): Int = Land.WALL_VERTICES + (land.rows - near) * land.stripVertices
}

/** HORIZON's colours (r, g, b each 0–1), from a palette (land.ts horizonTones). */
class HorizonTones(colors: PaletteColors) {
    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
    private fun mix(a: FloatArray, b: FloatArray, k: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * k }
    private val bg = rgb(colors.bg)
    private val accent = rgb(colors.accent)
    private val cool = rgb(colors.cool)

    /** The sky at the top and low down: a dark plum of the accent. */
    val skyTop = mix(bg, accent, 0.09f)
    val skyLow = mix(bg, accent, 0.24f)

    /** The glow (the accent) and its white-hot core. */
    val glow = accent
    val core = mix(accent, floatArrayOf(1f, 1f, 1f), 0.85f)

    /** The land's edges (the cool colour: SYNTH's teal) and its faces. */
    val line = mix(cool, bg, 0.3f)
    val face = mix(bg, cool, 0.05f)
}

/** The camera's distance after [dt] seconds at [speed], wrapped where the land repeats. */
fun advanceCamera(camZ: Float, dt: Float, speed: Float, land: Land): Float {
    val next = camZ + min(dt, HorizonParams.MAX_FRAME_STEP) * speed
    return next - floor(next / land.length) * land.length
}
