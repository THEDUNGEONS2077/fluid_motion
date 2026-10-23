/*
 * FrameDumpTest: writes what each scene hands the GPU, as the phone would
 * (an S25 Ultra at its default FHD+: 1080 × 2340 px, 2.8125 px per dp), to
 * app/build/shader-check/ (a JSON file per scene), for tools/shader-check to draw with the
 * real shader files in WebGL2. The numbers come from the same lib code the
 * renderers use (RainRecorder, NeuralFrame, VelocityFrame, FlowFrame, CityLayout and CityFrame), so the pictures
 * show what the phone will draw.
 *
 * Not a check by itself (it asserts only that it wrote something): the
 * harness looks at the result. Each scene is also dumped zoomed (ZOOM:
 * laid out for a screen 1/zoom as big, drawn zoom times as large, at the
 * same resolution), as the renderers lay it out.
 */
package com.thedungeons2077.fluidmotion.tools

import com.thedungeons2077.fluidmotion.lib.GlowLevel
import com.thedungeons2077.fluidmotion.lib.PaletteColors
import com.thedungeons2077.fluidmotion.lib.Palettes
import com.thedungeons2077.fluidmotion.lib.Quality
import com.thedungeons2077.fluidmotion.lib.desaturate
import com.thedungeons2077.fluidmotion.lib.flow.FlowBoard
import com.thedungeons2077.fluidmotion.lib.flow.FlowFrame
import com.thedungeons2077.fluidmotion.lib.flow.FlowParams
import com.thedungeons2077.fluidmotion.lib.flow.FlowSim
import com.thedungeons2077.fluidmotion.lib.flow.flowTones
import com.thedungeons2077.fluidmotion.lib.megacity.CityFrame
import com.thedungeons2077.fluidmotion.lib.megacity.CityLayout
import com.thedungeons2077.fluidmotion.lib.megacity.CitySim
import com.thedungeons2077.fluidmotion.lib.megacity.MegacityParams
import com.thedungeons2077.fluidmotion.lib.mixRgb
import com.thedungeons2077.fluidmotion.lib.neural.NeuralFrame
import com.thedungeons2077.fluidmotion.lib.neural.NeuralParams
import com.thedungeons2077.fluidmotion.lib.neural.NeuralSim
import com.thedungeons2077.fluidmotion.lib.rain.AtlasLayout
import com.thedungeons2077.fluidmotion.lib.rain.BASE_GLYPHS
import com.thedungeons2077.fluidmotion.lib.rain.KATAKANA
import com.thedungeons2077.fluidmotion.lib.rain.RainParams
import com.thedungeons2077.fluidmotion.lib.rain.RainRecorder
import com.thedungeons2077.fluidmotion.lib.rain.RainSim
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import com.thedungeons2077.fluidmotion.lib.rain.glyphSlots
import com.thedungeons2077.fluidmotion.lib.velocity.VelocityFrame
import com.thedungeons2077.fluidmotion.lib.velocity.VelocityParams
import com.thedungeons2077.fluidmotion.lib.velocity.VelocitySim
import com.thedungeons2077.fluidmotion.lib.velocity.velocityTones
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FrameDumpTest {
    private val out = File("build/shader-check").apply { mkdirs() }
    private val w = 1080
    private val h = 2340
    private val density = 2.8125f
    private val wDp = w / density
    private val hDp = h / density

    /** A tiny JSON writer (org.json is a stub in unit tests). */
    private class Json {
        val sb = StringBuilder("{")
        private var first = true
        private fun key(k: String) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(k).append("\":")
        }
        fun num(k: String, v: Number) = apply { key(k); sb.append(v) }
        fun str(k: String, v: String) = apply { key(k); sb.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"') }
        fun floats(k: String, v: FloatArray, n: Int = v.size) = apply { key(k); sb.append('['); for (i in 0 until n) { if (i > 0) sb.append(','); sb.append(v[i]) }; sb.append(']') }
        fun ints(k: String, v: IntArray) = apply { key(k); sb.append(v.joinToString(",", "[", "]")) }
        fun raw(k: String, json: String) = apply { key(k); sb.append(json) }
        fun done() = sb.append('}').toString()
    }

    private fun colors(c: PaletteColors) = Json()
        .num("bg", c.bg).num("text", c.text).num("textDim", c.textDim).num("textBright", c.textBright)
        .num("accent", c.accent).num("cool", c.cool).done()

    @Test
    fun `rain as the phone draws it`() {
        dumpRain(Quality.HIGH, "rain.json")
        // LOW draws a smaller trail layer with a smaller atlas: the same rain (the same seed), scaled up.
        dumpRain(Quality.LOW, "rain-low.json")
        // Zoomed in (150%): larger glyphs, fewer columns, the same layer.
        dumpRain(Quality.HIGH, "rain-zoom.json", 1.5f)
    }

    /** The rain at [quality] and [zoom], to [name]. */
    private fun dumpRain(quality: Quality, name: String, zoom: Float = 1f) {
        val px = density * quality.scale(density) * zoom
        val layerW = Math.round(w * quality.scale(density))
        val layerH = Math.round(h * quality.scale(density))
        val palette = Palettes.PHOSPHOR.colors
        val glow = RainParams.GLOW.getValue(GlowLevel.HIGH)
        val at = AtlasLayout(px, glow, KATAKANA.length + BASE_GLYPHS.length)
        val slots = glyphSlots(KATAKANA.length, BASE_GLYPHS.length)
        var buf = FloatArray(1 shl 16)
        var n = 0
        val fades = ArrayList<Int>()
        val recorder = RainRecorder(
            sink = { x, y, code, alpha ->
                if (n + 4 > buf.size) buf = buf.copyOf(buf.size * 2)
                buf[n++] = x; buf[n++] = y; buf[n++] = code; buf[n++] = alpha
            },
            fades = { fades.add(it) },
        )
        recorder.slotOf = slots
        val sim = RainSim(wDp / zoom, hDp / zoom, slots.size, Rng(20261023))
        sim.prewarm(recorder)
        val prewarm = buf.copyOf(n)
        // Then two seconds at 30 frames a second: each frame's fades (drawn first, as in a step), then its records.
        val frames = StringBuilder("[")
        repeat(60) { i ->
            n = 0
            fades.clear()
            sim.step(1f / 30f, recorder)
            if (i > 0) frames.append(',')
            frames.append("{\"fades\":").append(fades.joinToString(",", "[", "]"))
            frames.append(",\"records\":").append(buf.copyOf(n).joinToString(",", "[", "]")).append('}')
        }
        frames.append(']')
        val pitch = FloatArray(6) { if (it % 2 == 0) RainParams.LAYERS[it / 2].colW * px else RainParams.LAYERS[it / 2].rowH * px }
        val json = Json()
            .ints("screen", intArrayOf(w, h)).ints("layer", intArrayOf(layerW, layerH)).num("pxPerDp", px).str("quality", quality.label)
            .raw("glow", Json().num("alpha", glow.alpha).num("head", glow.head).num("headLayers", glow.headLayers).num("body", glow.body).done())
            .raw(
                "atlas",
                Json().num("width", at.width).num("height", at.height).num("perRow", AtlasLayout.PER_ROW)
                    .num("padHead", at.padHead).num("padBody", at.padBody).num("padErase", at.padErase).num("padSlot", at.padSlot)
                    .ints("cellW", at.cellW).ints("cellH", at.cellH).ints("fontPx", at.fontPx).ints("originY", at.originY).done(),
            )
            .str("kana", KATAKANA).str("base", BASE_GLYPHS)
            .floats("pitch", pitch).num("fadeStripe", RainParams.FADE_STRIPE).num("fadePhases", RainParams.FADE_PHASES).num("fadeAlpha", RainParams.FADE_ALPHA).num("eraseStroke", 0.5f * px)
            .raw("colors", colors(palette))
            .num("body", desaturate(palette.text, RainParams.BODY_DESATURATE)).num("dim", desaturate(palette.textDim, RainParams.BODY_DESATURATE))
            .floats("prewarm", prewarm).raw("frames", frames.toString())
            .done()
        File(out, name).writeText(json)
        assertTrue(prewarm.size > 1000)
    }

    @Test
    fun `neural net as the phone draws it`() {
        dumpNeural(1f, "neural.json")
        // Zoomed in (150%): fewer nodes, each larger.
        dumpNeural(1.5f, "neural-zoom.json")
    }

    /** The neural net at [zoom], to [name]. */
    private fun dumpNeural(zoom: Float, name: String) {
        val palette = Palettes.BLOOD.colors
        val glow = NeuralParams.GLOW.getValue(GlowLevel.HIGH)
        val px = density * zoom
        val pxLow = density * Quality.LOW.scale(density) * zoom
        val sim = NeuralSim(wDp / zoom, hDp / zoom, Rng(20261023), zoom)
        // Twelve seconds in: signals running, nodes lit.
        repeat(12 * 30) { sim.advance(1f / 30f) }
        val nodes = FloatArray(NeuralFrame.MAX_NODES * 2)
        val sparks = FloatArray(NeuralFrame.MAX_SPARKS * 4)
        val bokeh = FloatArray(NeuralFrame.MAX_BOKEH * 4)
        val pairs = NeuralFrame.nodes(sim, nodes)
        val ns = NeuralFrame.sparks(sim, glow.haloSize, px, sparks)
        // At LOW the sprites are sized for its px per dp (a whole number of its px).
        val sparksLow = FloatArray(NeuralFrame.MAX_SPARKS * 4)
        NeuralFrame.sparks(sim, glow.haloSize, pxLow, sparksLow)
        val nb = NeuralFrame.bokeh(sim, bokeh)
        val links = FloatArray(sim.links.size / 2 * 3) { i -> if (i % 3 == 2) sim.weight[i / 3] else sim.links[2 * (i / 3) + i % 3].toFloat() }
        val json = Json()
            .ints("screen", intArrayOf(w, h)).num("density", px).floats("dp", floatArrayOf(sim.w, sim.h))
            .num("lowScale", Quality.LOW.scale(density))
            .raw("colors", colors(palette)).num("coolLifted", mixRgb(palette.cool, 0xffffff, 0.4f))
            .raw("glow", Json().num("halo", glow.halo).num("haloSize", glow.haloSize).num("lineGlow", glow.lineGlow).done())
            .num("lineWidth", NeuralParams.LINE_WIDTH).num("glowWidth", NeuralFrame.GLOW_WIDTH).num("maxLink", sim.maxLink)
            .num("edge", 1f / NeuralFrame.spriteScale(glow.haloSize, px))
            .num("edgeLow", 1f / NeuralFrame.spriteScale(glow.haloSize, pxLow))
            .floats("links", links).floats("nodes", nodes, pairs * 4).floats("bokeh", bokeh, nb * 4).floats("sparks", sparks, ns * 4).floats("sparksLow", sparksLow, ns * 4)
            .done()
        File(out, name).writeText(json)
        assertTrue(ns > 0)
    }

    @Test
    fun `velocity as the phone draws it`() {
        dumpVelocity(1f, "velocity.json")
        // Zoomed in (150%): wider panels, longer streaks, a larger flare.
        dumpVelocity(1.5f, "velocity-zoom.json")
    }

    /** VELOCITY at [zoom], to [name]. */
    private fun dumpVelocity(zoom: Float, name: String) {
        val palette = Palettes.BLOOD.colors
        val g = VelocityParams.GLOW.getValue(GlowLevel.HIGH)
        val px = density * zoom
        val rng = Rng(20261023)
        val sim = VelocitySim(wDp / zoom, hDp / zoom, rng, zoom)
        val tile = VelocityFrame.striationTile(rng)
        // Eight seconds in: streaks in flight.
        repeat(8 * 30) { sim.advance(1f / 30f) }
        val panels = FloatArray(16)
        val lines = FloatArray(VelocityParams.LONG_LINES * 4)
        val streaks = FloatArray(VelocityParams.MAX_STREAKS * 4)
        val looks = FloatArray(VelocityParams.MAX_STREAKS * 4)
        val box = FloatArray(4)
        val np = VelocityFrame.panels(sim, g, panels)
        val nl = VelocityFrame.lines(sim, g, lines)
        val ns = VelocityFrame.streaks(sim, streaks, looks)
        VelocityFrame.arcBox(sim, px, box)
        val boxLow = FloatArray(4)
        VelocityFrame.arcBox(sim, density * Quality.LOW.scale(density) * zoom, boxLow)
        val tones = velocityTones(palette)
        val json = Json()
            .ints("screen", intArrayOf(w, h)).num("density", px).floats("dp", floatArrayOf(sim.w, sim.h))
            .num("lowScale", Quality.LOW.scale(density))
            .raw("colors", colors(palette))
            .raw("tones", Json().num("pale", tones.pale).num("red", tones.red).num("flare", tones.flare).done())
            .num("g", g).num("soft", VelocityParams.SOFT_EDGE).num("striation", VelocityParams.STRIATION)
            .ints("tile", IntArray(tile.size) { tile[it].toInt() and 255 })
            .floats("panels", panels, np * 4).floats("lines", lines, nl * 4).floats("streaks", streaks, ns * 4).floats("looks", looks, ns * 4)
            .floats("bloom", floatArrayOf(sim.flareX, sim.flareY, sim.flareR)).floats("arc", floatArrayOf(sim.arcX, sim.arcY, sim.arcR))
            .floats("span", floatArrayOf(VelocityParams.ARC_FROM, VelocityParams.ARC_TO)).num("arcHalf", VelocityParams.ARC_WIDTH / 2f)
            .num("arcAlpha", VelocityParams.ARC_ALPHA).num("strength", VelocityFrame.flareStrength(sim, g)).floats("arcBox", box).floats("arcBoxLow", boxLow)
            .done()
        File(out, name).writeText(json)
        assertTrue(np == 4)
    }

    @Test
    fun `data flow as the phone draws it`() {
        dumpFlow(1f, "flow.json")
        // Zoomed in (150%), and out (50%: the most board there is to draw).
        dumpFlow(1.5f, "flow-zoom.json")
        dumpFlow(0.5f, "flow-zoom-out.json")
    }

    /** DATA FLOW at [zoom], to [name]. */
    private fun dumpFlow(zoom: Float, name: String) {
        val palette = Palettes.ICE.colors
        val tones = flowTones(palette)
        // One random source for the board and then its motion, as the renderer has.
        val rng = Rng(20261023)
        val board = FlowBoard.build(wDp / zoom, hDp / zoom, rng)
        val sim = FlowSim(board, rng)
        // Six seconds in: packets running, a volley or two, vias flashing. Then on until a chip is taking data in,
        // so every kind of light is drawn.
        repeat(6 * 30) { sim.advance(1f / 30f) }
        var more = 0
        while (sim.chipGlow.max() < 0.3f && more++ < 30 * 30) sim.advance(1f / 30f)
        /** The light at a glow, as FlowFrame.light packs it: its arrays, cut to their counts. */
        fun light(glow: com.thedungeons2077.fluidmotion.lib.flow.FlowGlow): String {
            val segs = FloatArray(FlowFrame.lightSegCapacity(board) * FlowFrame.SEG)
            val marks = FloatArray((FlowParams.MAX_PULSES + FlowParams.MAX_FLASHES) * FlowFrame.MARK)
            val chips = FloatArray(board.chips.size * FlowFrame.CHIP)
            val counts = IntArray(3)
            FlowFrame.light(sim, tones, glow, segs, marks, chips, counts, FloatArray(2))
            return Json().floats("segs", segs, counts[0] * FlowFrame.SEG).floats("marks", marks, counts[1] * FlowFrame.MARK)
                .floats("chips", chips, counts[2] * FlowFrame.CHIP).done()
        }
        val high = FlowParams.glow(GlowLevel.HIGH, Quality.HIGH.linkGlow)
        val low = FlowParams.glow(GlowLevel.HIGH, Quality.LOW.linkGlow)
        val json = Json()
            .ints("screen", intArrayOf(w, h)).num("density", density * zoom).floats("dp", floatArrayOf(board.w, board.h))
            .num("lowScale", Quality.LOW.scale(density))
            .raw("colors", colors(palette))
            .raw(
                "tones",
                Json().num("dot", tones.dot).num("warmVia", tones.warmVia).num("coolVia", tones.coolVia)
                    .num("warmLight", tones.warmLight).num("coolLight", tones.coolLight).num("warmHead", tones.warmHead).num("coolHead", tones.coolHead)
                    .num("chipBody", tones.chipBody).num("chipEdge", tones.chipEdge).num("die", tones.die).done(),
            )
            .num("pitch", FlowParams.PITCH).floats("main", floatArrayOf(board.mainX, board.mainY)).num("far", FlowFrame.far(board))
            .floats("boardSegs", FlowFrame.boardSegs(board, tones, high)).floats("boardSegsLow", FlowFrame.boardSegs(board, tones, low))
            .floats("boardMarks", FlowFrame.boardMarks(board)).floats("boardChips", FlowFrame.boardChips(board))
            .raw("light", light(high)).raw("lightLow", light(low))
            .num("traces", board.traces.size).num("pulses", sim.pulses).num("flashes", sim.flashes)
            .done()
        File(out, name).writeText(json)
        assertTrue(sim.pulses > 0 && sim.chipGlow.max() >= 0.3f)
    }

    @Test
    fun `megacity as the phone draws it`() {
        dumpMegacity(1f, "megacity.json")
        // Zoomed in (150%): larger towers and windows; the figure keeps her place.
        dumpMegacity(1.5f, "megacity-zoom.json")
    }

    /** MEGACITY at [zoom], to [name]: its skyline texture, foreground and a frame's lights, eight seconds in. */
    private fun dumpMegacity(zoom: Float, name: String) {
        val palette = Palettes.NEON.colors
        val tones = CityLayout.tones(palette)
        val px = density * zoom
        val rng = Rng(20261024)
        val city = CityLayout.build((wDp / zoom).toDouble(), (hDp / zoom).toDouble(), rng)
        val sim = CitySim(city, rng)
        repeat(8 * 30) { sim.advance(1f / 30f) }
        val signs = FloatArray(CityFrame.signRoom(city))
        val glows = FloatArray(CityFrame.glowRoom(city))
        val scratch = FloatArray(3)
        val ns = CityFrame.signs(sim, tones, GlowLevel.HIGH, signs, scratch)
        val ng = CityFrame.glows(sim, tones, GlowLevel.HIGH, glows, scratch)
        fun rgb(c: FloatArray) = c
        val p = MegacityParams
        val json = Json()
            .ints("screen", intArrayOf(w, h)).num("density", px).num("lowScale", Quality.LOW.scale(density))
            .num("cols", city.cols).num("rows", city.rows).num("layers", city.layers.size).num("height", city.h.toFloat()).num("time", sim.time)
            .floats("cells", FloatArray(p.LAYERS.size * 2) { if (it % 2 == 0) p.LAYERS[it / 2].cellW else p.LAYERS[it / 2].cellH })
            .floats("fogs", FloatArray(p.LAYERS.size) { p.LAYERS[it].fog })
            .raw(
                "tones",
                Json().floats("skyTop", rgb(tones.skyTop)).floats("skyMid", tones.skyMid).floats("skyLow", tones.skyLow).floats("lowGlow", tones.lowGlow)
                    .floats("faceFar", tones.faceFar).floats("faceNear", tones.faceNear).floats("edge", tones.edge)
                    .floats("winPale", tones.winPale).floats("winViolet", tones.winViolet).floats("winPink", tones.winPink).floats("winWhite", tones.winWhite).done(),
            )
            .num("windowGlow", p.GLOW.getValue(GlowLevel.HIGH).window)
            .floats("skyline", CityLayout.skyline(city)).floats("foreground", CityLayout.foreground(city, tones, px))
            .floats("signs", signs, ns * CityFrame.SIGN).floats("glows", glows, ng * CityFrame.GLOW)
            .done()
        File(out, name).writeText(json)
        assertTrue(city.signs.isNotEmpty() && ng > 0)
    }
}

