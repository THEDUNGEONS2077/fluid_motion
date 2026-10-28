/*
 * scene/prism/PrismRenderer.kt: PRISM on the GPU: smoky glass blocks lit
 * magenta and blue from inside, drawn with cyberdeck_terminal's own shaders
 * (pr_*: one set, byte for byte).
 *
 * Layer: scene (GL). One pass, two draw calls, no depth buffer, no textures:
 *   1. what's behind the glass (pr_back.frag: navy, lighter low down, wide
 *      soft pools of light), one triangle over the screen;
 *   2. every block in one instanced draw (pr_box.vert/pr_box.frag), back to
 *      front (lib/prism/Prism.kt PrismFrame: an insertion sort into an order
 *      kept between frames), premultiplied glass: the body blends over what's
 *      behind, the light pools and the edges add. Each face draws its own
 *      smooth outline and edges, so there's no multisampling.
 * Per frame the CPU sorts a few hundred blocks, writes them and up to 16
 * lights into arrays made once, and sends them: nothing is allocated. QUALITY
 * below full resolution draws into a smaller frame scaled up after
 * (gl/ScaledTarget.kt). The phone's framing (a taller view on an upright
 * screen) is PrismView.
 */
package com.thedungeons2077.fluidmotion.scene.prism

import android.content.Context
import android.opengl.GLES30
import com.thedungeons2077.fluidmotion.gl.EmptyVao
import com.thedungeons2077.fluidmotion.gl.GlProgram
import com.thedungeons2077.fluidmotion.gl.ScaledTarget
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.Quality
import com.thedungeons2077.fluidmotion.lib.prism.BOX
import com.thedungeons2077.fluidmotion.lib.prism.PrismCamera
import com.thedungeons2077.fluidmotion.lib.prism.PrismField
import com.thedungeons2077.fluidmotion.lib.prism.PrismFrame
import com.thedungeons2077.fluidmotion.lib.prism.PrismMotion
import com.thedungeons2077.fluidmotion.lib.prism.PrismParams
import com.thedungeons2077.fluidmotion.lib.prism.PrismTones
import com.thedungeons2077.fluidmotion.lib.prism.PrismView
import com.thedungeons2077.fluidmotion.lib.prism.buildField
import com.thedungeons2077.fluidmotion.lib.prism.cubeVertices
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import com.thedungeons2077.fluidmotion.scene.Scene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** The PRISM scene. */
class PrismRenderer(context: Context) : Scene {
    private val back = GlProgram(context.assets, "pr_full.vert", "pr_back.frag")
    private val box = GlProgram(context.assets, "pr_box.vert", "pr_box.frag")
    private val empty = EmptyVao()
    private val target = ScaledTarget(context.assets)

    private var tones = PrismTones(Palettes.NEON.colors)
    private var glow = GlowLevel.DEFAULT
    private var quality = Quality.DEFAULT
    private var zoom = 1f
    private var screenW = 0
    private var screenH = 0
    private var density = 1f

    // The scene (made at the first size: its light pools spread over that screen's view), its motion and each frame's data.
    private var field: PrismField? = null
    private var motion: PrismMotion? = null
    private var frame: PrismFrame? = null
    private val camera = PrismCamera()
    private var view: PrismView? = null

    // The cube (per vertex) and the blocks (per instance), in one vertex array; the instances' CPU side made once.
    private var cubeVbo = 0
    private var instanceVbo = 0
    private var vao = 0
    private var instances: FloatBuffer? = null

    private var targetDirty = true

    override fun resize(width: Int, height: Int, density: Float) {
        if (width == screenW && height == screenH && density == this.density) return
        screenW = width
        screenH = height
        this.density = density
        targetDirty = true
    }

    /** The px per dp drawn at: the screen's (or fewer at a lower quality). The zoom narrows the view instead (PrismView), as the web's. */
    private val pxPerDp: Float get() = density * quality.scale(density)

    /** Makes the field (once) for the first framing, and the GPU's buffers for it. */
    private fun build(v: PrismView) {
        val f = buildField(Rng(System.nanoTime().toInt()), v.lightView)
        field = f
        motion = PrismMotion(f)
        frame = PrismFrame(f)
        instances = ByteBuffer.allocateDirect(f.count * BOX * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        cubeVbo = ids[0]
        instanceVbo = ids[1]
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glBindVertexArray(vao)
        val cube = cubeVertices()
        val cubeData = ByteBuffer.allocateDirect(cube.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(cube)
        cubeData.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cubeVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, cube.size * 4, cubeData, GLES30.GL_STATIC_DRAW)
        // Per vertex: the cube's corner (location 0) and its face's normal (1).
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
        // Per instance: the block's min corner (2) and size (3).
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, BOX * 4, 0)
        GLES30.glVertexAttribDivisor(2, 1)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 3, GLES30.GL_FLOAT, false, BOX * 4, 12)
        GLES30.glVertexAttribDivisor(3, 1)
        GLES30.glBindVertexArray(0)
    }

    override fun render(dt: Float) {
        if (screenW == 0) return
        if (targetDirty) {
            targetDirty = false
            target.resize(screenW, screenH, quality.scale(density))
            view = PrismView(target.width.toFloat(), target.height.toFloat(), pxPerDp, zoom)
        }
        val v = view ?: return
        if (field == null) build(v)
        val f = field ?: return
        val m = motion ?: return
        val fr = frame ?: return
        val buf = instances ?: return
        if (dt > 0f) m.advance(dt)
        val g = PrismParams.GLOW.getValue(glow)
        camera.update(m, v.aspect, v.fov)
        fr.pack(camera, m, tones, g.light)

        target.begin()
        // 1. What's behind the glass (every pixel: the clear only spares a tiled GPU loading the last frame).
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_BLEND)
        back.use()
        GLES30.glUniform3fv(back.u("uBg"), 1, tones.navy, 0)
        GLES30.glUniform3fv(back.u("uAccent"), 1, tones.magenta, 0)
        GLES30.glUniform3fv(back.u("uCool"), 1, tones.blue, 0)
        GLES30.glUniform2f(back.u("uAspect"), v.aspect, 1f)
        GLES30.glUniform1f(back.u("uLight"), g.light)
        empty.drawFullScreen()

        // 2. The blocks, back to front, premultiplied glass.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        box.use()
        GLES30.glUniformMatrix4fv(box.u("uViewProj"), 1, false, camera.viewProj, 0)
        GLES30.glUniform4fv(box.u("uLightPos"), fr.lights, fr.lightPos, 0)
        GLES30.glUniform3fv(box.u("uLightCol"), fr.lights, fr.lightCol, 0)
        GLES30.glUniform1i(box.u("uLights"), fr.lights)
        GLES30.glUniform3fv(box.u("uEye"), 1, camera.eye, 0)
        GLES30.glUniform3fv(box.u("uBody"), 1, tones.navy, 0)
        GLES30.glUniform3fv(box.u("uEdge"), 1, tones.edge, 0)
        GLES30.glUniform1f(box.u("uAlpha"), PrismParams.GLASS_FRONT)
        GLES30.glUniform1f(box.u("uFresnel"), PrismParams.FRESNEL)
        GLES30.glUniform1f(box.u("uDensity"), v.pxPerDp)
        GLES30.glUniform1f(box.u("uEdgeW"), PrismParams.EDGE_WIDTH)
        GLES30.glUniform1f(box.u("uEdgeGlow"), PrismParams.EDGE_GLOW)
        GLES30.glUniform1f(box.u("uGlowAmt"), g.edge)
        GLES30.glUniform1f(box.u("uFar"), PrismParams.DISTANCE * 2f)
        buf.position(0)
        buf.put(fr.sorted, 0, f.count * BOX)
        buf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, f.count * BOX * 4, buf, GLES30.GL_STREAM_DRAW)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 36, f.count)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
        target.end()
    }

    override fun setPalette(colors: PaletteColors) {
        tones = PrismTones(colors)
    }

    override fun setGlow(glow: GlowLevel) {
        this.glow = glow
    }

    override fun setQuality(quality: Quality) {
        if (quality == this.quality) return
        this.quality = quality
        targetDirty = true
    }

    override fun setZoom(zoom: Float) {
        if (zoom == this.zoom) return
        this.zoom = zoom
        targetDirty = true
    }

    override fun release() {
        if (cubeVbo != 0) GLES30.glDeleteBuffers(2, intArrayOf(cubeVbo, instanceVbo), 0)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        cubeVbo = 0
        instanceVbo = 0
        vao = 0
        target.release()
        empty.release()
        back.release()
        box.release()
        field = null
        motion = null
        frame = null
        view = null
    }
}
