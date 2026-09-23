package com.bm.spectrum.dsp

import org.jtransforms.fft.DoubleFFT_1D
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

/** Independent spectral inputs: pink must become flat, white +10 and brown -10 dB/dec. */
class RtaScaleTest {
    private val rate=48000
    private val n=144000
    private fun key(fraction:Int,hann:Boolean)=Settings(rtaFraction=fraction).let {
        PlanKey(n,rate,it.low,it.high,it.points(),it.width(),hann,fraction)
    }
    private fun slope(f:DoubleArray,p:DoubleArray):Double {
        val indices=f.indices.filter{f[it] in 100.0..10000.0}
        val x=indices.map{log10(f[it])};val y=indices.map{10*log10(p[it])}
        val mx=x.average();val my=y.average()
        return x.indices.sumOf{(x[it]-mx)*(y[it]-my)}/x.sumOf{(it-mx).pow(2)}
    }
    @Test fun gaussianCompensationHasTheCorrectSignAndTenDbPerDecade() {
        for(fraction in listOf(3,6,12)) {
            val plan=GaussianPlan(key(fraction,false))
            for(exponent in listOf(0.0,-1.0,-2.0)) {
                val psd=DoubleArray(n/2+1){if(it==0)0.0 else (it*rate.toDouble()/n).pow(exponent)}
                val output=DoubleArray(plan.frequencies.size);plan.apply(psd,output)
                val measured=slope(plan.frequencies,output)
                assertEquals("1/$fraction input PSD f^$exponent",10*(exponent+1),measured,0.025)
                if(exponent==-1.0) for(p in output) assertEquals(1.0,p,1e-12)
                println("kernel fraction=$fraction exponent=$exponent slope=$measured dB/dec")
            }
        }
    }
    private fun colored(exponent:Double,seed:Long):DoubleArray {
        val random=Random(seed);val bins=DoubleArray(2*n)
        for(k in 1 until n/2) {
            val magnitude=(k*rate.toDouble()/n).pow(exponent/2)
            val phase=random.nextDouble()*2*PI
            bins[2*k]=magnitude*cos(phase);bins[2*k+1]=magnitude*sin(phase)
            bins[2*(n-k)]=bins[2*k];bins[2*(n-k)+1]=-bins[2*k+1]
        }
        DoubleFFT_1D(n.toLong()).complexInverse(bins,true)
        return DoubleArray(n){bins[2*it]}
    }
    @Test fun actualGeneratorsAreFlatAfterOneDePinkCorrection() {
        val random = Random(23092026)
        val pink = Generators.pink(n, rate, 20.0, 20000.0, 0.9,
            DoubleArray(n / 2 + 1) { random.nextDouble() * 2 * PI })
        val chirp = Sweep(6.0, rate, 20.0, 20000.0).signal()
        for ((name, signal) in listOf("pink" to pink, "extended chirp" to chirp)) {
            val analyzer = Analyzer(PlanKey(signal.size, rate, 20.0, 20000.0, 256, 0.3, false), PlanCache())
            val pcm = DoubleArray(signal.size) { signal[it].toDouble() }
            val power = analyzer.analyze(pcm).copyOf()
            val measured = slope(analyzer.plan.frequencies, power)
            assertEquals("$name must be flat after de-pink", 0.0, measured, 0.1)
            val doubled = analyzer.analyze(DoubleArray(pcm.size) { pcm[it] * 2 })
            for (i in power.indices) {
                assertEquals("$name gain at ${analyzer.plan.frequencies[i]} Hz",
                    20 * log10(2.0), 10 * log10(doubled[i] / power[i]), 1e-8)
            }
            println("Actual $name compensated slope=$measured dB/dec; doubled PCM gives +6.0206 dB")
        }
    }
    @Test fun fftAndRtaWindowsPreserveTheExpectedCompensatedSlopes() {
        for(fraction in listOf(3,6,12)) for(hann in listOf(false,true)) {
            val analyzer=Analyzer(key(fraction,hann),PlanCache())
            for(exponent in listOf(0.0,-1.0)) {
                val average=DoubleArray(analyzer.key.points)
                // Hann mixes neighboring bins; average independent phase realizations.
                val runs=if(hann)8 else 1
                repeat(runs){seed->
                    val p=analyzer.analyze(colored(exponent,1234L+seed))
                    for(i in average.indices)average[i]+=p[i]/runs
                }
                val measured=slope(analyzer.plan.frequencies,average)
                assertEquals("FFT 1/$fraction hann=$hann PSD f^$exponent",10*(exponent+1),measured,if(hann)0.15 else 0.025)
                println("FFT fraction=$fraction hann=$hann exponent=$exponent slope=$measured dB/dec")
            }
        }
    }
}
