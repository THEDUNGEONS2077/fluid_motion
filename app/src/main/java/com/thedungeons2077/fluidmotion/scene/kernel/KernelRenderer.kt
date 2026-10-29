/*
 * scene/kernel/KernelRenderer.kt: QUANTUM KERNEL on the GPU: five depths of
 * code in the rain's glyphs scrolling up past each other on black, as the
 * dashboard's (0.27.1).
 *
 * Layer: scene (Android Canvas to draw the code once, GL to show it). Text
 * is the slow part, so each depth's code (lib/kernel/Kernel.kt) is drawn
 * once into a one-channel texture of its coverage, glow and focus blur
 * baked in, as the web's sheets: a wide haze and a tight bloom
 * (BlurMaskFilter, σ as the web's ctx.filter blur), then the glyphs, all
 * added. Its colour isn't in it: kernel.frag colours each depth from the
 * palette, so a palette change rebuilds nothing; a new size, zoom, quality
 * or glow level redraws the textures (at the next frame, the context
 * current). A frame is then one pass (kernel.frag): five texture reads a
 * pixel. Its loop's lines are drawn a loop above and below too, so the
 * glow and blur run across where the loop meets itself.
 *
 * QUALITY scales the textures (fewer texels to read, the pass at full
 * resolution); a blurred depth is drawn smaller still (DepthSpec.resolution).
 * Nothing is allocated per frame: the uniforms' arrays are made once.
 */
package com.thedungeons2077.fluidmotion.scene.kernel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.opengl.GLES30
import com.thedungeons2077.fluidmotion.R
import com.thedungeons2077.fluidmotion.gl.EmptyVao
import com.thedungeons2077.fluidmotion.gl.GlProgram
import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.Quality
import com.thedungeons2077.fluidmotion.lib.kernel.CodeLine
import com.thedungeons2077.fluidmotion.lib.kernel.KernelCode
import com.thedungeons2077.fluidmotion.lib.kernel.KernelMotion
import com.thedungeons2077.fluidmotion.lib.kernel.KernelParams
import com.thedungeons2077.fluidmotion.lib.kernel.KernelTones
import com.thedungeons2077.fluidmotion.lib.kernel.KernelView
import com.thedungeons2077.fluidmotion.lib.rain.KATAKANA
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import com.thedungeons2077.fluidmotion.scene.Scene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max

/** The QUANTUM KERNEL scene. */
class KernelRenderer(context: Context) : Scene {
    private val program = GlProgram(context.assets, "fullscreen.vert", "kernel.frag")
    private val empty = EmptyVao()
    private val typeface: Typeface = context.resources.getFont(R.font.jetbrains_mono_regular)
    private val rng = Rng(System.nanoTime().toInt())

    private var tones = KernelTones(Palettes.AZURE.colors)
    private var glow = GlowLevel.DEFAULT
    private var quality = Quality.DEFAULT
    private var zoom = 1f
    private var screenW = 0
    private var screenH = 0
    private var density = 1f
    private var dirty = true

    // The layout, its code (per depth), its motion and its textures (GL names, 0 = none yet).
    private var view: KernelView? = null
    private var code: List<List<CodeLine>> = emptyList()
    private var motion: KernelMotion? = null
    private val textures = IntArray(KernelParams.DEPTHS.size)

    // The uniforms' arrays, made once.
    private val loopH = FloatArray(KernelParams.DEPTHS.size)
    private val colours = FloatArray(KernelParams.DEPTHS.size * 3)

    override fun resize(width: Int, height: Int, density: Float) {
        if (width == screenW && height == screenH && density == this.density) return
        screenW = width
        screenH = height
        this.density = density
        dirty = true
    }

    /** Lays the code out for the screen (zoomed: as if 1/zoom as big) and draws every depth's texture. Context current. */
    private fun build() {
        dirty = false
        val v = KernelView(screenW.toFloat(), screenH.toFloat(), density * zoom)
        view = v
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@KernelRenderer.typeface
            textAlign = Paint.Align.CENTER
            color = 0xffffffff.toInt()
        }
        // Katakana only if some font on this phone has them (as the rain's).
        val kana = paint.hasGlyph(KATAKANA.substring(0, 1))
        val letters = KernelCode.letters(kana)
        code = KernelParams.DEPTHS.indices.map { d -> KernelCode.write(rng, v.cols[d], v.rows[d], v.lanes[d], KernelParams.DEPTHS[d], letters) }
        motion = KernelMotion(v, code[KernelParams.FOCUS_DEPTH], rng)
        for (d in KernelParams.DEPTHS.indices) {
            loopH[d] = v.loopH[d]
            upload(d, paintDepth(d, v, paint, kana))
        }
    }

    /**
     * Depth [d]'s loop as coverage (ALPHA_8): the glow (a wide haze, then a
     * tight bloom), then the glyphs, out of focus or sharp, all added, as the
     * web's sheet. Drawn at the quality's share of the screen's resolution,
     * times the depth's own.
     */
    private fun paintDepth(d: Int, v: KernelView, base: Paint, kana: Boolean): Bitmap {
        val s = KernelParams.DEPTHS[d]
        val scale = quality.scale(density) * s.resolution
        val g = KernelParams.GLOW.getValue(glow)
        val reach = s.glowReach * g * v.pxPerDp * scale
        val bitmap = Bitmap.createBitmap(max(1, ceil(screenW * scale).toInt()), max(1, ceil(v.loopH[d] * scale).toInt()), Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
        // The passes: (σ in bitmap px, opacity).
        val passes = ArrayList<Pair<Float, Float>>()
        if (reach > 0f && s.glow > 0f) {
            passes += reach * 0.5f to minOf(1f, s.glow * g * 0.7f)
            passes += reach * 0.15f to minOf(1f, s.glow * g * 0.8f)
        }
        passes += s.blur * v.pxPerDp * scale to s.alpha
        for ((sigma, alpha) in passes) {
            val p = Paint(base).apply {
                textSize = s.font * v.pxPerDp
                this.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
                xfermode = add
                if (s.weight > 0f) {
                    style = Paint.Style.FILL_AND_STROKE
                    strokeWidth = s.weight * v.pxPerDp
                }
                // A Gaussian of σ (bitmap px), in the canvas's own units (it's scaled), as a mask filter.
                blur(sigma / scale)?.let { maskFilter = it }
            }
            val fm = p.fontMetrics
            val lift = (fm.ascent + fm.descent) / 2f
            for (line in code[d]) {
                // The line, and its copies a loop above and below (for the glow and blur across the seam).
                for (shift in floatArrayOf(-v.loopH[d], 0f, v.loopH[d])) {
                    val cy = (line.row + 0.5f) * v.lineH[d] + shift
                    if (cy < -v.lineH[d] * 3f || cy > v.loopH[d] + v.lineH[d] * 3f) continue
                    for (i in line.text.indices) {
                        val ch = line.text[i]
                        if (ch == ' ') continue
                        val cx = (line.col + i + 0.5f) * v.colW[d]
                        if (cx > screenW + v.colW[d]) break
                        val mirrored = kana && ch in KATAKANA
                        canvas.save()
                        if (mirrored) canvas.scale(-1f, 1f, cx, cy)
                        canvas.drawText(line.text, i, i + 1, cx, cy - lift, p)
                        canvas.restore()
                    }
                }
            }
        }
        return bitmap
    }

    /** Uploads depth [d]'s coverage as a one-channel texture (linear, repeating down its loop), freeing the bitmap. */
    private fun upload(d: Int, bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val plane = ByteArray(bitmap.rowBytes * h)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(plane))
        bitmap.recycle()
        // Rows packed tight (an ALPHA_8 bitmap's rows may be padded).
        val data = ByteBuffer.allocateDirect(w * h).order(ByteOrder.nativeOrder())
        for (y in 0 until h) data.put(plane, y * (plane.size / h), w)
        data.position(0)
        if (textures[d] == 0) textures[d] = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[d])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, data)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    override fun render(dt: Float) {
        if (screenW == 0) return
        if (dirty) build()
        val v = view ?: return
        val m = motion ?: return
        if (dt > 0f) m.advance(dt)
        for (d in KernelParams.DEPTHS.indices) {
            val c = tones.tones[KernelParams.DEPTHS[d].tone]
            colours[d * 3] = c[0]
            colours[d * 3 + 1] = c[1]
            colours[d * 3 + 2] = c[2]
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenW, screenH)
        GLES30.glDisable(GLES30.GL_BLEND)
        program.use()
        // Each depth's texture on its own unit (kernel.frag reads them by name: a sampler array can't be indexed in a loop in GLSL ES 3.00).
        for (d in textures.indices) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + d)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[d])
        }
        GLES30.glUniform1i(program.u("uDepth0"), 0)
        GLES30.glUniform1i(program.u("uDepth1"), 1)
        GLES30.glUniform1i(program.u("uDepth2"), 2)
        GLES30.glUniform1i(program.u("uDepth3"), 3)
        GLES30.glUniform1i(program.u("uDepth4"), 4)
        GLES30.glUniform1fv(program.u("uOffset"), KernelParams.DEPTHS.size, m.offset, 0)
        GLES30.glUniform1fv(program.u("uLoopH"), KernelParams.DEPTHS.size, loopH, 0)
        GLES30.glUniform3fv(program.u("uColour"), KernelParams.DEPTHS.size, colours, 0)
        GLES30.glUniform2f(program.u("uScreen"), screenW.toFloat(), screenH.toFloat())
        GLES30.glUniform3fv(program.u("uBack"), 1, tones.back, 0)
        // The line being run: its patch on screen now (px), padded by twice its glow's reach, as the web's.
        val f = KernelParams.FOCUS_DEPTH
        val line = if (m.runLine >= 0) code[f].getOrNull(m.runLine) else null
        if (line != null) {
            val pad = KernelParams.DEPTHS[f].glowReach * 2f * v.pxPerDp
            val top = m.screenY(f, line.row) - pad
            GLES30.glUniform4f(program.u("uRunRect"), line.col * v.colW[f] - pad, top, (line.col + line.text.length) * v.colW[f] + pad, top + v.lineH[f] + 2f * pad)
            GLES30.glUniform1f(program.u("uRun"), m.runStrength)
        } else {
            GLES30.glUniform4f(program.u("uRunRect"), 0f, 0f, 0f, 0f)
            GLES30.glUniform1f(program.u("uRun"), 0f)
        }
        empty.drawFullScreen()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    override fun setPalette(colors: PaletteColors) {
        tones = KernelTones(colors)
    }

    override fun setGlow(glow: GlowLevel) {
        if (glow == this.glow) return
        this.glow = glow
        dirty = true
    }

    override fun setQuality(quality: Quality) {
        if (quality == this.quality) return
        this.quality = quality
        dirty = true
    }

    override fun setZoom(zoom: Float) {
        if (zoom == this.zoom) return
        this.zoom = zoom
        dirty = true
    }

    override fun release() {
        val live = textures.filter { it != 0 }.toIntArray()
        if (live.isNotEmpty()) GLES30.glDeleteTextures(live.size, live, 0)
        textures.fill(0)
        empty.release()
        program.release()
        view = null
        motion = null
    }

    private companion object {
        /** A Gaussian blur of standard deviation [sigma] (canvas units) as a mask filter (Skia's radius: σ = 0.57735 · r + 0.5), or null if too small to see. */
        fun blur(sigma: Float): BlurMaskFilter? {
            val radius = (sigma - 0.5f) / 0.57735f
            return if (radius > 0.1f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
        }
    }
}
