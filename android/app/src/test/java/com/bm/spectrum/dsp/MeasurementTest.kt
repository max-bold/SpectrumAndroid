package com.bm.spectrum.dsp

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class MeasurementTest {
    private val rate = 48000
    private fun input(n: Int) = FloatArray(n) { i ->
        // Nonstationary recording makes a Welch-only final result observably different.
        ((if (i < n/2) 0.1 else 0.6) * sin(2*PI*937*i/rate) + 0.03*cos(2*PI*321*i/rate)).toFloat()
    }
    @Test fun spectrumFinalUsesWholeRecordingRegardlessOfPreview() {
        val settings = Settings(mode="Spectrum",duration=0.5,low=100.0,high=5000.0,welchSize=4096,welchHop=2048)
        val pcm=input(24000)
        val results=mutableListOf<MeasurementPlot>()
        val cache=PlanCache()
        val m=Measurement(settings,rate,cache,results::add)
        var offset=0
        while(offset<pcm.size) { val n=min(733,pcm.size-offset); m.push(pcm.copyOfRange(offset,offset+n),n); offset+=n }
        assertTrue(results.any { it.kind=="welch" && it.fftSize==4096 })
        m.finish()
        val final=results.last()
        assertEquals("periodogram",final.kind); assertEquals(pcm.size,final.fftSize)
        val expected=Analyzer(PlanKey(pcm.size,rate,100.0,5000.0,256,0.3,false),PlanCache()).analyze(DoubleArray(pcm.size){pcm[it].toDouble()})
        assertArrayEquals(DoubleArray(expected.size){10*log10(expected[it].coerceAtLeast(1e-20))},final.db,1e-10)
        val withoutPreview=mutableListOf<MeasurementPlot>()
        val offline=Measurement(settings.copy(onlineWelch=false),rate,cache,withoutPreview::add)
        offline.push(pcm,pcm.size); offline.finish()
        assertArrayEquals(final.db,withoutPreview.single().db,1e-10)
        val builds=cache.builds
        Measurement(settings,rate,cache) {}
        assertEquals(builds,cache.builds)
    }
    @Test fun earlyStopBeforeWelchFrameStillProducesPeriodogram() {
        val result=mutableListOf<MeasurementPlot>()
        val m=Measurement(Settings(mode="Spectrum",duration=1.0),rate,PlanCache(),result::add)
        val pcm=input(3000);m.push(pcm,pcm.size);m.finish()
        assertEquals("periodogram",result.single().kind)
        assertEquals(3000,result.single().fftSize)
        assertEquals(3000.0/rate,result.single().elapsed,1e-12)
    }
    @Test fun rtaWindowFollowsGeneratorAndGainIsSixDbPerDoubling() {
        val cache=PlanCache()
        for(generator in listOf(false,true)) {
            val s=Settings(rtaWidth=0.1,rtaHop=0.1,generatorEnabled=generator)
            fun run(gain:Double):MeasurementPlot {
                val plots=mutableListOf<MeasurementPlot>()
                val m=Measurement(s,rate,cache,plots::add)
                val pcm=input(4800).map{(it*gain).toFloat()}.toFloatArray()
                m.push(pcm,pcm.size);m.finish();return plots.last()
            }
            val a=run(1.0);val b=run(2.0);val c=run(0.5)
            for(i in a.db.indices) {
                assertEquals(20*log10(2.0),b.db[i]-a.db[i],1e-8)
                assertEquals(-20*log10(2.0),c.db[i]-a.db[i],1e-8)
            }
            // Use precisely the same float capture representation as the engine.
            val check=Analyzer(PlanKey(4800,rate,s.low,s.high,s.points(),s.width(),!generator,s.rtaFraction),PlanCache())
                .analyze(input(4800).map{it.toDouble()}.toDoubleArray())
            assertArrayEquals(DoubleArray(check.size){10*log10(check[it].coerceAtLeast(1e-20))},a.db,1e-10)
        }
        assertEquals(2,cache.builds)
    }
    @Test fun extendedSweepPreservesWorkingBandAndFadesOutsideIt() {
        val sweep=Sweep(5.0,rate,100.0,2000.0)
        assertEquals(6*rate,sweep.samples)
        assertEquals(100.0/20.0.pow(0.1),sweep.startFrequency,1e-10)
        assertEquals(100.0,sweep.frequency(0.5),1e-10)
        assertEquals(2000.0,sweep.frequency(5.5),1e-8)
        val signal=sweep.signal()
        assertEquals(0f,signal.first(),0f);assertEquals(0f,signal.last(),0f)
        assertTrue(signal.all{it.isFinite() && abs(it)<=0.900001f})
        assertEquals(1.0,fadeGain(rate,rate/2),0.0)
        assertEquals(0.0,fadeGain(0,rate/2),0.0)
        val wide=Sweep(0.5,rate,20.0,20000.0)
        assertEquals(20000.0,wide.frequency(1.0),1e-7)
        assertTrue(wide.frequency(1.5)<rate/2.0)
        assertTrue(wide.signal().all { it.isFinite() })
        val m=Measurement(Settings(mode="Spectrum",duration=0.5,generatorEnabled=true),rate,PlanCache()) {}
        assertEquals(wide.samples,m.total)
    }
    @Test fun longRecordingSmoothingStaysBoundedAndMatchesCachedRows() {
        val k=PlanKey(30*rate,rate,20.0,20000.0,256,0.3,false)
        val plan=GaussianPlan(k)
        assertEquals(0L,plan.bytes)
        val power=DoubleArray(k.size/2+1){if(it==0)0.0 else (1+0.2*sin(it*0.001))/(it*rate.toDouble()/k.size)}
        val actual=DoubleArray(k.points);plan.apply(power,actual)
        // The same rows with Hann in the cache key force stored coefficients.
        // A 32-point logarithmic grid shares endpoints with the 256-point grid.
        val small=GaussianPlan(k.copy(points=32,hann=true))
        val expected=DoubleArray(32);small.apply(power,expected)
        assertEquals(expected.first(),actual.first(),1e-11)
        assertEquals(expected.last(),actual.last(),1e-11)
        assertTrue(actual.all{it.isFinite() && it>0})
    }
}
