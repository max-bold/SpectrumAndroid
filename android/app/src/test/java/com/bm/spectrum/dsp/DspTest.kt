package com.bm.spectrum.dsp

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.*

class DspTest {
    private val ref = JSONObject(javaClass.getResource("/reference.json")!!.readText())
    private fun JSONArray.doubles() = DoubleArray(length()) { getDouble(it) }
    private fun key(n: Int, hann: Boolean = true) = PlanKey(n,8000,20.0,3500.0,64,0.3,hann)
    private fun close(expected: DoubleArray, actual: DoubleArray, tolerance: Double = 1e-9) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("bin $i", expected[i], actual[i], tolerance * max(1.0, abs(expected[i])))
    }
    @Test fun periodogramsMatchBmSpectrumAndScipy() {
        val cases = ref.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val analyzer = Analyzer(key(c.getInt("n"),c.getBoolean("hann")),PlanCache())
            val input = c.getJSONArray("signal").doubles()
            close(c.getJSONArray("expected").doubles(),analyzer.analyze(input))
            // FFT scratch is reused; a subsequent silent frame must not contain old bins.
            assertTrue(analyzer.analyze(DoubleArray(input.size)).all { it == 0.0 })
            close(c.getJSONArray("expected").doubles(),analyzer.analyze(input))
        }
    }
    @Test fun streamingWelchMatchesScipyAcrossIrregularChunks() {
        val c = ref.getJSONObject("welch")
        val input = c.getJSONArray("signal").doubles().map { it.toFloat() }.toFloatArray()
        val cache = PlanCache()
        val analyzer = Analyzer(key(2048),cache)
        val average = PowerAverage(64)
        val stream = FrameStream(2048,512) { average.add(analyzer.analyze(it)) }
        var offset=0
        while(offset<input.size) {
            val count=min(137+(offset%251),input.size-offset)
            stream.push(input.copyOfRange(offset,offset+count),count); offset+=count
        }
        assertEquals(1+(input.size-2048)/512,average.count)
        assertEquals(1,cache.builds)
        close(c.getJSONArray("expected").doubles(),average.power,2e-8)
    }
    @Test fun frameOverlapIsExact() {
        val frames=mutableListOf<DoubleArray>()
        val stream=FrameStream(4,2) { frames.add(it.copyOf()) }
        stream.push(floatArrayOf(0f,1f,2f),3)
        stream.push(floatArrayOf(3f,4f,5f,6f,7f,8f),6)
        assertEquals(3,frames.size)
        close(doubleArrayOf(0.0,1.0,2.0,3.0),frames[0])
        close(doubleArrayOf(4.0,5.0,6.0,7.0),frames[2])
    }
    @Test fun cacheReusesAndInvalidatesAllRelevantParameters() {
        val cache=PlanCache()
        val k=key(4096)
        val first=cache.get(k)
        assertSame(first,cache.get(k))
        assertEquals(1,cache.builds)
        for (changed in listOf(k.copy(size=4095),k.copy(sampleRate=9000),k.copy(low=30.0),k.copy(high=3000.0),k.copy(points=32),k.copy(width=0.4))) {
            assertNotSame(first,cache.get(changed))
        }
        assertEquals(7,cache.builds)
    }
    @Test fun inverseFrequencyPowerBecomesFlat() {
        val k=key(24000)
        val plan=GaussianPlan(k)
        val psd=DoubleArray(k.size/2+1) { if(it==0) 0.0 else 1.0/(it*8000.0/k.size) }
        val out=DoubleArray(k.points); plan.apply(psd,out)
        close(DoubleArray(k.points){1.0},out)
    }
    @Test fun chirpAndPinkMatchNumpyIncludingOddLengthAndNyquist() {
        close(ref.getJSONArray("chirp").doubles(),Generators.chirp(1024,8000,20.0,3500.0,0.5).map{it.toDouble()}.toDoubleArray(),1e-7)
        val cases=ref.getJSONArray("pink")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i)
            val actual=Generators.pink(c.getInt("n"),8000,20.0,3500.0,0.5,c.getJSONArray("phases").doubles())
            close(c.getJSONArray("expected").doubles(),actual.map{it.toDouble()}.toDoubleArray(),1e-7)
            assertEquals(0.5,actual.maxOf{abs(it)}.toDouble(),1e-7)
        }
    }
    @Test fun rtaUsesStandardCentersAndBandIndependentWidth() {
        assertEquals(log2(10.0.pow(0.1)),Settings().width(),1e-12)
        val third=OctaveBands.centers(20.0,20000.0,3)
        assertEquals(31,third.size)
        assertEquals(19.9526231497,third.first(),1e-8)
        assertEquals(19952.6231497,third.last(),1e-7)
        assertTrue(third.any { abs(it-1000)<1e-10 })
        for (fraction in listOf(6,12)) {
            val full=OctaveBands.centers(20.0,20000.0,fraction)
            val cropped=OctaveBands.centers(100.0,2000.0,fraction)
            assertTrue(cropped.all { it in full.toList() })
            assertFalse(full.any { abs(it-1000)<1e-8 })
            val centerAbove=full.first { it>1000 }
            assertEquals(1000*10.0.pow(0.3*0.5/fraction),centerAbove,1e-8)
            val settings=Settings(low=100.0,high=2000.0,rtaFraction=fraction)
            val k=PlanKey(144000,48000,100.0,2000.0,settings.points(),settings.width(),true,fraction)
            val cache=PlanCache()
            assertArrayEquals(cropped,cache.get(k).frequencies,0.0)
            assertSame(cache.get(k),cache.get(k))
        }
        Settings().validate()
        assertThrows(IllegalArgumentException::class.java) { Settings(rtaHop=4.0).validate() }
        assertThrows(IllegalArgumentException::class.java) { Settings(mode="Spectrum",duration=0.5,welchSize=65536).validate() }
    }
}
