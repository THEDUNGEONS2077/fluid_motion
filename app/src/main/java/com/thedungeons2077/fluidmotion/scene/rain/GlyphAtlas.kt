/*
 * scene/rain/GlyphAtlas.kt: the rain's glyphs, drawn once into one texture
 * so each frame only copies from it (cyberdeck_terminal's glyphAtlas.ts).
 *
 * Layer: scene (Android Canvas to draw, GL to upload). Text rendering and
 * blurring are the slow part of a matrix rain; here they happen once per
 * screen and glow level, never per frame.
 *
 * The web keeps a row per colour style. On the GPU colour is cheap, so
 * each glyph is drawn once, into four channels, and the shader
 * (rain_ops.frag) colours it from the palette:
 *   R: the glyph (its core);
 *   G: the glyph blurred as a head's glow (canvas shadowBlur → Gaussian σ = blur/2);
 *   B: the glyph blurred as a body's glow;
 *   A: the glyph a touch bolder (filled and stroked): the shape that erases it.
 * A palette change then needs no new atlas (the rain's colours just change),
 * only a glow change does. All three layers' glyphs share the texture, so a
 * whole frame of the rain is one draw call.
 *
 * Each glyph has a slot: its cell plus a margin as wide as the head glow
 * reaches (GLOW_REACH). Bodies use a smaller part of it (their glow reaches
 * less), so the GPU never fills more than the web's sprite would.
 */
package com.thedungeons2077.fluidmotion.scene.rain

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import com.thedungeons2077.fluidmotion.lib.rain.RainGlow
import com.thedungeons2077.fluidmotion.lib.rain.AtlasLayout
import com.thedungeons2077.fluidmotion.lib.rain.BASE_GLYPHS
import com.thedungeons2077.fluidmotion.lib.rain.KATAKANA
import com.thedungeons2077.fluidmotion.lib.rain.RainParams
import com.thedungeons2077.fluidmotion.lib.rain.glyphSlots
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** How much bolder the erase shapes are than the glyphs (stroke width, dp), so a glyph's soft edges go too. */
private const val ERASE_STROKE = 0.5f

/**
 * The glyph sheet: a texture, where each layer's slots are, and how the
 * simulation's glyph numbers map onto slots (katakana are listed twice in
 * the glyph list, as on the web: they dominate the reference).
 */
class GlyphAtlas private constructor(
    /** The GL texture (RGBA8, nearest: copies are pixel for pixel). */
    val texture: Int,
    val width: Int,
    val height: Int,
    /** Per layer: the cell's size in px (rounded up) and where its slots start (y). */
    val cellW: IntArray,
    val cellH: IntArray,
    val originY: IntArray,
    /** Margins round a cell (px): a head's glow, a body's, an erase's, and the slot's (the largest). */
    val padHead: Int,
    val padBody: Int,
    val padErase: Int,
    val padSlot: Int,
    /** The simulation's glyph count (the length of its glyph list). */
    val glyphCount: Int,
    /** Slot of each glyph number. */
    val slotOf: IntArray,
) {
    /** Slots per row in the texture. */
    val perRow: Int get() = AtlasLayout.PER_ROW

    /** Frees the texture (context current). */
    fun release() = GLES30.glDeleteTextures(1, intArrayOf(texture), 0)

    companion object {
        /**
         * Draws the sheet for a [pxPerDp] (render target px per dp) and a
         * [glow], in [typeface] (system fonts fill in what it lacks:
         * katakana), and uploads it. Context current.
         */
        fun build(typeface: Typeface, pxPerDp: Float, glow: RainGlow): GlyphAtlas {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                textAlign = Paint.Align.CENTER
                color = 0xffffffff.toInt()
            }
            // Katakana only if some font on this phone has them (nearly every Android has Noto Sans CJK).
            val kana = paint.hasGlyph(KATAKANA.substring(0, 1))
            val unique = (if (kana) KATAKANA else "") + BASE_GLYPHS
            val mirrored = BooleanArray(unique.length) { kana && it < KATAKANA.length }
            // The simulation's glyph list: katakana twice, then the rest (glyphAtlas.ts glyphSet).
            val slotOf = glyphSlots(if (kana) KATAKANA.length else 0, BASE_GLYPHS.length)

            // Where everything goes (lib/rain/AtlasLayout.kt: shared with the shader and tools/shader-check).
            val at = AtlasLayout(pxPerDp, glow, unique.length)
            val layers = RainParams.LAYERS
            val width = at.width
            val height = at.height

            // The four channels, each drawn in its own alpha bitmap.
            val core = Paint(paint)
            val headGlow = Paint(paint).apply { blur(glow.head * pxPerDp / 2f)?.let { maskFilter = it } }
            val bodyGlow = Paint(paint).apply { blur(glow.body * pxPerDp / 2f)?.let { maskFilter = it } }
            val erase = Paint(paint).apply {
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = ERASE_STROKE * pxPerDp
            }
            val channels = arrayOf(core, headGlow, bodyGlow, erase).map { p ->
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                val canvas = Canvas(bitmap)
                for (l in layers.indices) {
                    p.textSize = at.fontPx[l].toFloat()
                    // textBaseline 'middle': the em box's middle on the slot's centre.
                    val fm = p.fontMetrics
                    val lift = (fm.ascent + fm.descent) / 2f
                    for (s in unique.indices) {
                        val cx = at.centreX(l, s)
                        val cy = at.centreY(l, s)
                        canvas.save()
                        if (mirrored[s]) canvas.scale(-1f, 1f, cx, cy)
                        canvas.drawText(unique, s, s + 1, cx, cy - lift, p)
                        canvas.restore()
                    }
                }
                bitmap
            }

            // Interleave the four alpha planes into RGBA (in a heap array: one bulk copy after), then upload.
            val stride = channels[0].rowBytes
            val planes = channels.map { b ->
                val plane = ByteArray(b.rowBytes * b.height)
                b.copyPixelsToBuffer(ByteBuffer.wrap(plane))
                b.recycle()
                plane
            }
            val r = planes[0]
            val g = planes[1]
            val bl = planes[2]
            val a = planes[3]
            val out = ByteArray(width * height * 4)
            var o = 0
            for (y in 0 until height) {
                var i = y * stride
                for (x in 0 until width) {
                    out[o] = r[i]
                    out[o + 1] = g[i]
                    out[o + 2] = bl[i]
                    out[o + 3] = a[i]
                    o += 4
                    i++
                }
            }
            val rgba = ByteBuffer.allocateDirect(out.size).order(ByteOrder.nativeOrder())
            rgba.put(out).position(0)
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            return GlyphAtlas(ids[0], width, height, at.cellW, at.cellH, at.originY, at.padHead, at.padBody, at.padErase, at.padSlot, slotOf.size, slotOf)
        }

        /**
         * A Gaussian blur of standard deviation [sigma] px, as a mask filter
         * (Skia takes a "radius": σ = 0.57735 · r + 0.5), or null if too small to see.
         */
        private fun blur(sigma: Float): BlurMaskFilter? {
            val radius = (sigma - 0.5f) / 0.57735f
            return if (radius > 0.1f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
        }
    }
}
