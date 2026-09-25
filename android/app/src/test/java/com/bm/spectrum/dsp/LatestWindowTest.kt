package com.bm.spectrum.dsp

import org.junit.Assert.*
import org.junit.Test

class LatestWindowTest {
    @Test fun slowRtaAnalysisUsesCurrentSignalAndSkipsMissedHops() {
        val rate = 48000
        val size = 4800
        val hop = 960
        val latest = LatestWindow(size, hop)
        val plots = mutableListOf<MeasurementPlot>()
        val measurement = Measurement(Settings(rtaWidth=0.1, rtaHop=0.02, generatorEnabled=true),
            rate, PlanCache(), plots::add)
        fun tone(start: Int, count: Int, gain: Double) = FloatArray(count) { i ->
            (gain * kotlin.math.sin(2 * Math.PI * 1000 * (start + i) / rate)).toFloat()
        }

        latest.add(tone(0, size, 0.1), size)
        val first = latest.newestAfter(0)!!
        measurement.pushLatest(first)
        // Simulate the producer recording 10 hops while the analyzer is busy.
        repeat(10) { step -> latest.add(tone(size + step * hop, hop, 0.2), hop) }
        val next = latest.newestAfter(first.captured)!!
        assertEquals((size + 10 * hop).toLong(), next.captured)
        measurement.pushLatest(next)
        assertEquals(2, measurement.frames)
        assertEquals(2, plots.size)
        val peak = plots.first().frequency.indices.minBy { i ->
            kotlin.math.abs(kotlin.math.ln(plots.first().frequency[i] / 1000.0))
        }
        assertEquals(20 * kotlin.math.log10(2.0), plots.last().db[peak] - plots.first().db[peak], 0.01)
    }

    @Test fun slowConsumerReceivesNewestCompleteWindowWithoutBacklog() {
        val buffer = LatestWindow(8, 3)
        buffer.add(floatArrayOf(1f, 2f, 3f, 4f), 4)
        assertNull(buffer.newestAfter(0))
        buffer.add(floatArrayOf(5f, 6f, 7f, 8f), 4)
        val first = buffer.newestAfter(0)!!
        assertEquals(8L, first.captured)
        assertArrayEquals((1..8).map(Int::toDouble).toDoubleArray(), first.pcm, 0.0)
        assertNull(buffer.newestAfter(8))

        // Producer advances far beyond several hops while the consumer is busy.
        buffer.add((9..24).map(Int::toFloat).toFloatArray(), 16)
        val latest = buffer.newestAfter(first.captured)!!
        assertEquals(24L, latest.captured)
        assertArrayEquals((17..24).map(Int::toDouble).toDoubleArray(), latest.pcm, 0.0)
        assertArrayEquals((1..8).map(Int::toDouble).toDoubleArray(), first.pcm, 0.0)
        assertNull(buffer.newestAfter(latest.captured))
        buffer.add(floatArrayOf(25f, 26f, 27f), 3)
        assertArrayEquals((20..27).map(Int::toDouble).toDoubleArray(), buffer.newestAfter(24)!!.pcm, 0.0)
    }

}
