/*
 * scene/flow/FlowRenderer.kt: DATA FLOW on the GPU: the circuit board
 * from lib/flow/FlowBoard.kt with the data from lib/flow/FlowSim.kt,
 * drawn as cyberdeck_terminal draws it.
 *
 * Layer: scene (GL). One pass, nine small draw calls:
 *   1. the board's background, its glows and the dot grid, worked out per
 *      pixel (flow_bg.frag): no texture to read;
 *   2. the board itself, drawn over: its traces (and pin stubs), vias and
 *      pads, chips: instances sent once per board, palette, glow and
 *      quality;
 *   3. the light, added: chips taking data in, afterglow, packets (one
 *      instance per stretch of trace a packet covers, its fade worked out
 *      in the shader), heads and flashes: a few kilobytes a frame, into
 *      arrays made once.
 * Nothing is allocated per frame. QUALITY below full resolution draws into
 * a smaller frame scaled up after (gl/ScaledTarget.kt); LOW also leaves
 * out the board's soft glow and the packets' halos. ZOOM lays the board out
 * for a screen 1/zoom as big and draws it zoom times as large.
 */
package com.thedungeons2077.fluidmotion.scene.flow

import android.content.Context
import android.opengl.GLES30
import com.thedungeons2077.fluidmotion.gl.EmptyVao
import com.thedungeons2077.fluidmotion.gl.GlProgram
import com.thedungeons2077.fluidmotion.gl.InstanceStream
import com.thedungeons2077.fluidmotion.gl.ScaledTarget
import com.thedungeons2077.fluidmotion.gl.StaticInstances
import com.thedungeons2077.fluidmotion.gl.uniformRgb
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.Quality
import com.thedungeons2077.fluidmotion.lib.flow.FlowBoard
import com.thedungeons2077.fluidmotion.lib.flow.FlowFrame
import com.thedungeons2077.fluidmotion.lib.flow.FlowGlow
import com.thedungeons2077.fluidmotion.lib.flow.FlowParams
import com.thedungeons2077.fluidmotion.lib.flow.FlowSim
import com.thedungeons2077.fluidmotion.lib.flow.FlowTones
import com.thedungeons2077.fluidmotion.lib.flow.flowTones
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import com.thedungeons2077.fluidmotion.scene.Scene
import kotlin.math.min

/** The DATA FLOW scene. */
class FlowRenderer(context: Context) : Scene {
    private val bg = GlProgram(context.assets, "fullscreen.vert", "flow_bg.frag")
    private val seg = GlProgram(context.assets, "flow_seg.vert", "flow_seg.frag")
    private val mark = GlProgram(context.assets, "flow_mark.vert", "flow_mark.frag")
    private val chip = GlProgram(context.assets, "flow_chip.vert", "flow_chip.frag")
    private val empty = EmptyVao()
    private val target = ScaledTarget(context.assets)

    private var colors: PaletteColors = Palettes.ICE.colors
    private var tones: FlowTones = flowTones(colors)
    private var glow = GlowLevel.DEFAULT
    private var quality = Quality.DEFAULT
    private var board: FlowBoard? = null
    private var sim: FlowSim? = null
    private var screenW = 0
    private var screenH = 0
    private var density = 1f
    private var zoom = 1f
    // A new zoom: the board is laid out again at the next frame (with the context current).
    private var layoutDirty = false

    // The board's instances (sent once per board, palette, glow and quality).
    private var boardSegs: StaticInstances? = null
    private var boardMarks: StaticInstances? = null
    private var boardChips: StaticInstances? = null

    // The light's: arrays filled each frame (lib/flow/FlowFrame.kt), streamed to the GPU.
    private var segArray = FloatArray(0)
    private val markArray = FloatArray((FlowParams.MAX_PULSES + FlowParams.MAX_FLASHES) * FlowFrame.MARK)
    private var chipArray = FloatArray(0)
    private var lightSegs: InstanceStream? = null
    private val lightMarks = stream(FlowFrame.MARK, FlowParams.MAX_PULSES + FlowParams.MAX_FLASHES, intArrayOf(4, 2))
    private var lightChips: InstanceStream? = null
    private val counts = IntArray(3)
    private val at = FloatArray(2)

    // The glow in force, worked out with the board (LOW quality leaves out the soft glows and halos).
    private var glowNow: FlowGlow = FlowParams.glow(glow, quality.linkGlow)

    // Set every frame (the chip program draws the board's chips, then their glow).
    private val chipPass = chip.u("uPass")

    private var boardDirty = true
    private var targetDirty = true
    private var dirty = true

    /** A stream of instances of [stride] floats, its attributes [sizes] floats each, in order. */
    private fun stream(stride: Int, capacity: Int, sizes: IntArray) = InstanceStream(stride, capacity).apply {
        var offset = 0
        sizes.forEachIndexed { loc, size ->
            attribute(loc, size, offset)
            offset += size
        }
    }

    /** Static instances of [stride] floats from [src], their attributes [sizes] floats each. */
    private fun static(stride: Int, src: FloatArray, sizes: IntArray) = StaticInstances(stride, src).apply {
        var offset = 0
        sizes.forEachIndexed { loc, size ->
            attribute(loc, size, offset)
            offset += size
        }
    }

    override fun resize(width: Int, height: Int, density: Float) {
        if (width == screenW && height == screenH && density == this.density && sim != null) return
        screenW = width
        screenH = height
        this.density = density
        build()
    }

    /** A new board at the zoom: laid out for a screen 1/zoom as big (dp), drawn zoom times as large. */
    private fun build() {
        layoutDirty = false
        val dp = density * zoom
        val rng = Rng(System.nanoTime().toInt())
        val b = FlowBoard.build(screenW / dp, screenH / dp, rng)
        board = b
        sim = FlowSim(b, rng)
        // Room for the light on this board, made once.
        segArray = FloatArray(FlowFrame.lightSegCapacity(b) * FlowFrame.SEG)
        chipArray = FloatArray(b.chips.size * FlowFrame.CHIP)
        lightSegs?.release()
        lightSegs = stream(FlowFrame.SEG, FlowFrame.lightSegCapacity(b), intArrayOf(4, 4, 4, 4))
        lightChips?.release()
        lightChips = stream(FlowFrame.CHIP, maxOf(1, b.chips.size), intArrayOf(4, 2))
        boardDirty = true
        targetDirty = true
        dirty = true
    }

    /** The px per dp drawn at: the screen's (or fewer at a lower quality), times the zoom. */
    private val pxPerDp: Float get() = density * quality.scale(density) * zoom

    /** The board's instances, for its palette, glow and quality (and the glow the light is drawn with). */
    private fun sendBoard(b: FlowBoard) {
        boardDirty = false
        glowNow = FlowParams.glow(glow, quality.linkGlow)
        boardSegs?.release()
        boardMarks?.release()
        boardChips?.release()
        boardSegs = static(FlowFrame.SEG, FlowFrame.boardSegs(b, tones, glowNow), intArrayOf(4, 4, 4, 4))
        boardMarks = static(FlowFrame.MARK, FlowFrame.boardMarks(b), intArrayOf(4, 2))
        boardChips = static(FlowFrame.CHIP, FlowFrame.boardChips(b), intArrayOf(4, 2))
    }

    override fun render(dt: Float) {
        if (layoutDirty && screenW > 0) build()
        val s = sim ?: return
        val b = board ?: return
        if (dt > 0f) s.advance(min(dt, MAX_FRAME_STEP))
        if (targetDirty) {
            targetDirty = false
            target.resize(screenW, screenH, quality.scale(density))
            dirty = true
        }
        if (boardDirty) sendBoard(b)
        if (dirty) setUniforms(b)
        FlowFrame.light(s, tones, glowNow, segArray, markArray, chipArray, counts, at)

        target.begin()
        // 1. The board's background (every pixel: the clear only spares a tiled GPU loading the last frame).
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_BLEND)
        bg.use()
        empty.drawFullScreen()
        // 2. The board, drawn over.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        seg.use()
        boardSegs?.drawQuads()
        mark.use()
        boardMarks?.drawQuads()
        chip.use()
        GLES30.glUniform1i(chipPass, 0)
        boardChips?.drawQuads()
        // 3. The light, added (the chip program still current: their glow first).
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glUniform1i(chipPass, 1)
        lightChips?.let {
            it.load(chipArray, counts[2])
            it.drawQuads()
        }
        seg.use()
        lightSegs?.let {
            it.load(segArray, counts[0])
            it.drawQuads()
        }
        mark.use()
        lightMarks.load(markArray, counts[1])
        lightMarks.drawQuads()
        GLES30.glDisable(GLES30.GL_BLEND)
        target.end()
    }

    /** Uniforms that hold until the screen, palette, glow or quality changes. */
    private fun setUniforms(b: FlowBoard) {
        dirty = false
        bg.use()
        uniformRgb(bg.u("uBg"), colors.bg)
        uniformRgb(bg.u("uCool"), colors.cool)
        uniformRgb(bg.u("uAccent"), colors.accent)
        uniformRgb(bg.u("uDot"), tones.dot)
        GLES30.glUniform2f(bg.u("uScreen"), b.w, b.h)
        GLES30.glUniform1f(bg.u("uDensity"), pxPerDp)
        GLES30.glUniform1f(bg.u("uPitch"), FlowParams.PITCH)
        GLES30.glUniform2f(bg.u("uMain"), b.mainX, b.mainY)
        GLES30.glUniform1f(bg.u("uFar"), FlowFrame.far(b))
        seg.use()
        GLES30.glUniform2f(seg.u("uTargetPx"), target.width.toFloat(), target.height.toFloat())
        GLES30.glUniform1f(seg.u("uDensity"), pxPerDp)
        mark.use()
        GLES30.glUniform2f(mark.u("uTargetPx"), target.width.toFloat(), target.height.toFloat())
        GLES30.glUniform1f(mark.u("uDensity"), pxPerDp)
        uniformRgb(mark.u("uBg"), colors.bg)
        uniformRgb(mark.u("uWarmVia"), tones.warmVia)
        uniformRgb(mark.u("uCoolVia"), tones.coolVia)
        uniformRgb(mark.u("uWarmLight"), tones.warmLight)
        uniformRgb(mark.u("uCoolLight"), tones.coolLight)
        uniformRgb(mark.u("uWarmHead"), tones.warmHead)
        uniformRgb(mark.u("uCoolHead"), tones.coolHead)
        chip.use()
        GLES30.glUniform2f(chip.u("uTargetPx"), target.width.toFloat(), target.height.toFloat())
        GLES30.glUniform1f(chip.u("uDensity"), pxPerDp)
        GLES30.glUniform1f(chip.u("uPitch"), FlowParams.PITCH)
        uniformRgb(chip.u("uBody"), tones.chipBody)
        uniformRgb(chip.u("uEdge"), tones.chipEdge)
        uniformRgb(chip.u("uDie"), tones.die)
        uniformRgb(chip.u("uLight"), tones.warmLight)
    }

    override fun setPalette(colors: PaletteColors) {
        this.colors = colors
        tones = flowTones(colors)
        boardDirty = true
        dirty = true
    }

    override fun setGlow(glow: GlowLevel) {
        if (glow == this.glow) return
        this.glow = glow
        boardDirty = true
    }

    override fun setQuality(quality: Quality) {
        if (quality == this.quality) return
        this.quality = quality
        targetDirty = true
        boardDirty = true
    }

    override fun setZoom(zoom: Float) {
        if (zoom == this.zoom) return
        this.zoom = zoom
        if (sim != null) layoutDirty = true
    }

    override fun release() {
        boardSegs?.release()
        boardMarks?.release()
        boardChips?.release()
        lightSegs?.release()
        lightMarks.release()
        lightChips?.release()
        target.release()
        empty.release()
        bg.release()
        seg.release()
        mark.release()
        chip.release()
        sim = null
        board = null
    }

    private companion object {
        /** The longest step the motion takes in one frame (s). */
        const val MAX_FRAME_STEP = 0.1f
    }
}
