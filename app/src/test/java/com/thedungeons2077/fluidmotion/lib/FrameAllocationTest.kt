/*
 * FrameAllocationTest: a frame allocates nothing. Each wallpaper's work for
 * a frame (its motion and the packing its renderer sends the GPU, called
 * as the renderer calls them) is run a thousand times, and what this thread
 * allocated meanwhile weighed. Garbage made per frame wakes the collector
 * now and then, on a phone that should be asleep between frames.
 */
package com.thedungeons2077.fluidmotion.lib

import com.thedungeons2077.fluidmotion.lib.flow.FlowBoard
import com.thedungeons2077.fluidmotion.lib.flow.FlowFrame
import com.thedungeons2077.fluidmotion.lib.flow.FlowParams
import com.thedungeons2077.fluidmotion.lib.flow.FlowSim
import com.thedungeons2077.fluidmotion.lib.flow.flowTones
import com.thedungeons2077.fluidmotion.lib.megacity.CityFrame
import com.thedungeons2077.fluidmotion.lib.megacity.CityLayout
import com.thedungeons2077.fluidmotion.lib.megacity.CitySim
import com.thedungeons2077.fluidmotion.lib.neural.NeuralFrame
import com.thedungeons2077.fluidmotion.lib.neural.NeuralParams
import com.thedungeons2077.fluidmotion.lib.neural.NeuralSim
import com.thedungeons2077.fluidmotion.lib.rain.BASE_GLYPHS
import com.thedungeons2077.fluidmotion.lib.rain.KATAKANA
import com.thedungeons2077.fluidmotion.lib.rain.RainRecorder
import com.thedungeons2077.fluidmotion.lib.rain.RainSim
import com.thedungeons2077.fluidmotion.lib.rain.Rng
import com.thedungeons2077.fluidmotion.lib.rain.glyphSlots
import com.thedungeons2077.fluidmotion.lib.velocity.VelocityFrame
import com.thedungeons2077.fluidmotion.lib.velocity.VelocityParams
import com.thedungeons2077.fluidmotion.lib.velocity.VelocitySim
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAllocationTest {
    // An S25 Ultra's screen (dp) at its default FHD+, and its px per dp.
    private val w = 384f
    private val h = 832f
    private val density = 2.8125f

    /**
     * Runs [frame] (after ten seconds' warm-up) in three windows of a
     * thousand frames and checks the lightest allocated nothing. A one-off
     * of the JVM's own (a constant resolved, compiled code swapped in) lands
     * in one window; anything the frames do, every frame or every packet,
     * lands in all three.
     */
    private fun weigh(name: String, frame: () -> Unit) {
        repeat(300) { frame() }
        val idle = allocatedBytes().let { allocatedBytes() - it }
        val windows = LongArray(3) {
            val before = allocatedBytes()
            repeat(1000) { frame() }
            allocatedBytes() - before - idle
        }
        val allocated = windows.min()
        println("$name: $allocated bytes in 1000 frames (windows ${windows.joinToString()})")
        // Not a byte a frame: one object a frame would be 16 000 bytes, one per packet over 1500.
        assertTrue("$name allocated $allocated bytes in 1000 frames", allocated < 256)
    }

    @Test
    fun megacity() {
        val city = CityLayout.build(w.toDouble(), h.toDouble(), Rng(6))
        val sim = CitySim(city, Rng(7))
        val tones = CityLayout.tones(Palettes.NEON.colors)
        val signs = FloatArray(CityFrame.signRoom(city))
        val glows = FloatArray(CityFrame.glowRoom(city))
        val scratch = FloatArray(3)
        weigh("megacity") {
            sim.advance(1f / 30f)
            CityFrame.signs(sim, tones, GlowLevel.HIGH, signs, scratch)
            CityFrame.glows(sim, tones, GlowLevel.HIGH, glows, scratch)
        }
    }

    private companion object {
        // This thread's count of bytes allocated, by the JVM. Reached by reflection: unit tests compile against
        // Android's library, which hasn't java.lang.management, and run on a desktop JVM, which has.
        val bean: Any = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)!!
        val counter: java.lang.reflect.Method = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)

        fun allocatedBytes(): Long = counter.invoke(bean, Thread.currentThread().id) as Long

        init {
            // JDK 17's reflection builds itself a faster way in after 15 calls, allocating as it does: done here, before any weighing.
            repeat(32) { allocatedBytes() }
        }
    }

    @Test
    fun `matrix rain`() {
        val slots = glyphSlots(KATAKANA.length, BASE_GLYPHS.length)
        val records = FloatArray(1 shl 16)
        var n = 0
        val recorder = RainRecorder(
            sink = { x, y, code, alpha ->
                if (n + 4 > records.size) n = 0
                records[n++] = x; records[n++] = y; records[n++] = code; records[n++] = alpha
            },
            fades = { },
        )
        recorder.slotOf = slots
        val sim = RainSim(w, h, slots.size, Rng(1))
        sim.prewarm(recorder)
        weigh("rain") { sim.step(1f / 30f, recorder) }
    }

    @Test
    fun `neural net`() {
        val sim = NeuralSim(w, h, Rng(2))
        val g = NeuralParams.GLOW.getValue(GlowLevel.HIGH)
        val nodes = FloatArray(NeuralFrame.MAX_NODES * 2)
        val sparks = FloatArray(NeuralFrame.MAX_SPARKS * 4)
        val bokeh = FloatArray(NeuralFrame.MAX_BOKEH * 4)
        weigh("neural") {
            sim.advance(1f / 30f)
            NeuralFrame.bokeh(sim, bokeh)
            NeuralFrame.nodes(sim, nodes)
            NeuralFrame.sparks(sim, g.haloSize, density, sparks)
        }
    }

    @Test
    fun velocity() {
        val sim = VelocitySim(w, h, Rng(3))
        val g = VelocityParams.GLOW.getValue(GlowLevel.HIGH)
        val panels = FloatArray(16)
        val lines = FloatArray(VelocityParams.LONG_LINES * 4)
        val streaks = FloatArray(VelocityParams.MAX_STREAKS * 4)
        val looks = FloatArray(VelocityParams.MAX_STREAKS * 4)
        val box = FloatArray(4)
        weigh("velocity") {
            sim.advance(1f / 30f)
            VelocityFrame.panels(sim, g, panels)
            VelocityFrame.lines(sim, g, lines)
            VelocityFrame.streaks(sim, streaks, looks)
            VelocityFrame.flareStrength(sim, g)
            VelocityFrame.arcBox(sim, density, box)
        }
    }

    @Test
    fun `data flow`() {
        val board = FlowBoard.build(w, h, Rng(4))
        val sim = FlowSim(board, Rng(5))
        val tones = flowTones(Palettes.ICE.colors)
        val glow = FlowParams.glow(GlowLevel.HIGH, linkGlow = true)
        val segs = FloatArray(FlowFrame.lightSegCapacity(board) * FlowFrame.SEG)
        val marks = FloatArray((FlowParams.MAX_PULSES + FlowParams.MAX_FLASHES) * FlowFrame.MARK)
        val chips = FloatArray(board.chips.size * FlowFrame.CHIP)
        val counts = IntArray(3)
        val at = FloatArray(2)
        weigh("data flow") {
            sim.advance(1f / 30f)
            FlowFrame.light(sim, tones, glow, segs, marks, chips, counts, at)
        }
    }
}
