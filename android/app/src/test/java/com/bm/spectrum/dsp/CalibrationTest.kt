package com.bm.spectrum.dsp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import java.util.Random

class CalibrationTest {
    @Test fun calibrationFadesKeepAllAnalyzedPeriodsUntouched() {
        val rate=8000
        val period=Generators.pink(3*rate,rate,20.0,3500.0,0.4,DoubleArray(3*rate/2+1){Random(it.toLong()).nextDouble()*2*PI})
        val signal=calibrationPlayback(period,rate)
        val fade=(CalibrationLimits.FADE_SECONDS*rate).roundToInt()
        assertEquals(3*period.size+2*fade,signal.size)
        assertEquals(0f,signal.first(),0f);assertEquals(0f,signal.last(),0f)
        for(i in 0 until 3*period.size) assertEquals(period[i%period.size],signal[fade+i],0f)
        val capture=FloatArray(12*rate)
        signal.copyInto(capture,rate/2)
        assertEquals(rate/2+fade,CalibrationAnalysis.align(capture,period,rate))
        val ambient=FloatArray(3*rate){0.00001f*sin(2*PI*137*it/rate).toFloat()}
        val curve=CalibrationAnalysis.measure(capture,capture,ambient,ambient,period,rate,7,9)
        assertTrue(curve.correctionDb.all { abs(it)<1e-6 })
        val random=Random(91)
        val noisyTail=FloatArray(3*rate){if(it<rate) ambient[it] else (random.nextDouble()*0.6-0.3).toFloat()}
        val error=assertThrows(IllegalArgumentException::class.java) {
            CalibrationAnalysis.measure(capture,capture,noisyTail,ambient,period,rate,7,9)
        }
        assertTrue(error.message!!.contains("Low SNR: built-in"))
    }
    private fun curve() = CalibrationCurve(7,48000,9,
        DoubleArray(128){100.0*100.0.pow(it/127.0)},
        DoubleArray(128){-5.0+10.0*it/127.0},100.0,10000.0)

    @Test fun signalPsdNormalizationDoesNotOverflowAtProductionRate() {
        val rate = 48000
        val period = rate * CalibrationLimits.PERIOD_SECONDS
        assertEquals(2.57201646090535e-10, calibrationSignalPsdScale(rate, period), 1e-22)
    }

    @Test fun snrAndValidityRequireBothChannelsAndFrequencyLimits() {
        assertEquals(20.0,sourceSnrDb(101.0,1.0),1e-10)
        assertTrue(sourceSnrDb(102.0,1.0)>20.0)
        assertTrue(calibrationBinValid(1000.0,48000,21.0,21.0,0.9))
        assertFalse(calibrationBinValid(1000.0,48000,20.0,21.0,0.9))
        assertFalse(calibrationBinValid(1000.0,48000,21.0,20.0,0.9))
        assertFalse(calibrationBinValid(1000.0,48000,21.0,21.0,0.89))
        assertFalse(calibrationBinValid(19.9,48000,30.0,30.0,1.0))
        assertFalse(calibrationBinValid(4000.1,8000,30.0,30.0,1.0))
    }

    @Test fun identityRangeAndInterpolation() {
        val c=curve();c.validate()
        assertTrue(c.matches(7,48000,9))
        assertFalse(c.matches(8,48000,9))
        assertFalse(c.matches(7,44100,9))
        assertFalse(c.matches(7,48000,6))
        assertNull(c.correction(99.0));assertNull(c.correction(10001.0))
        assertEquals(0.0,c.correction(1000.0)!!,0.05)
        assertEquals(100.0,c.restricted(20.0,20000.0).first,0.0)
        assertEquals(10000.0,c.restricted(20.0,20000.0).second,0.0)
        assertThrows(IllegalArgumentException::class.java){c.restricted(20.0,50.0)}
        c.copy(correctionDb=DoubleArray(128){20.0}).validate()
        assertThrows(IllegalArgumentException::class.java){c.copy(correctionDb=DoubleArray(128){20.01}).validate()}
        assertThrows(IllegalArgumentException::class.java){c.copy(fMax=24001.0).validate()}
    }

    @Test fun correctionIsAppliedToSpectralPowerBeforeSmoothing() {
        val rate=8000
        val correction=CalibrationCurve(7,rate,9,
            DoubleArray(128){100.0*30.0.pow(it/127.0)},DoubleArray(128){6.020599913},100.0,3000.0)
        val key=PlanKey(8000,rate,100.0,3000.0,64,0.3,false)
        val signal=DoubleArray(key.size){0.2*sin(2*PI*1000*it/rate)}
        val plain=Analyzer(key,PlanCache()).analyze(signal).copyOf()
        val corrected=Analyzer(key,PlanCache(),correction).analyze(signal)
        val peak=plain.indices.maxBy{plain[it]}
        assertEquals(4.0,corrected[peak]/plain[peak],0.03)
    }

    @Test fun alignmentAndSmoothTransferFromSequentialPasses() {
        val rate=8000;val n=3*rate
        val phaseRandom=Random(123)
        val phases=DoubleArray(n/2+1){phaseRandom.nextDouble()*2*PI}
        val excitation=Generators.pink(n,rate,20.0,3500.0,0.35,phases)
        val phone=FloatArray(12*rate);val reference=FloatArray(12*rate)
        val random=Random(42)
        for(i in phone.indices) {
            phone[i]=(random.nextGaussian()*0.00001).toFloat()
            reference[i]=(random.nextGaussian()*0.00001).toFloat()
        }
        val phoneAmbient=FloatArray(rate){(random.nextGaussian()*0.00001).toFloat()}
        val refAmbient=FloatArray(rate){(random.nextGaussian()*0.00001).toFloat()}
        val phoneStart=4200;val refStart=4700
        // A smooth first-order response, independently recorded with a fixed latency.
        for(i in 0 until 3*n) {
            val x=excitation[i%n]
            val previous=excitation[(i-1+n)%n]
            phone[phoneStart+i]+=x
            reference[refStart+i]+=(1.5*x+0.15*(x-previous)).toFloat()
        }
        assertTrue(abs(phoneStart-CalibrationAnalysis.align(phone,excitation,rate))<=2)
        assertTrue(abs(refStart-CalibrationAnalysis.align(reference,excitation,rate))<=2)
        val c=CalibrationAnalysis.measure(phone,reference,phoneAmbient,refAmbient,excitation,rate,7,9)
        c.validate()
        assertTrue(c.fMin>=20.0 && c.fMax<=rate/2.0)
        assertTrue(c.averageRepeatability>0.9)
        val spectra=c.spectra!!
        assertEquals(128,spectra.frequencies.size)
        assertTrue(spectra.phoneSignalDb.all { it.isFinite() })
        assertTrue(spectra.phoneSignalDb[64]>spectra.phoneNoiseDb[64]+20)
        assertTrue(spectra.referenceSignalDb[64]>spectra.referenceNoiseDb[64]+20)
        assertTrue(c.phoneSnrDb>20.0 && c.referenceSnrDb>20.0)
        assertEquals(0.0,c.correction(1000.0)!!,0.1)
        val transferAt3k=20*log10(hypot(1.5+0.15*(1-cos(2*PI*3000/rate)),0.15*sin(2*PI*3000/rate)))
        val transferAt1k=20*log10(hypot(1.5+0.15*(1-cos(2*PI*1000/rate)),0.15*sin(2*PI*1000/rate)))
        assertEquals(transferAt3k-transferAt1k,c.correction(3000.0)!!,0.8)
        assertTrue(c.correctionDb.all{it in -CalibrationLimits.MAX_CORRECTION_DB..CalibrationLimits.MAX_CORRECTION_DB})
        val loudAmbient=FloatArray(rate){(random.nextDouble()*0.4-0.2).toFloat()}
        val lowSnr=assertThrows(IllegalArgumentException::class.java){CalibrationAnalysis.measure(phone,reference,loudAmbient,refAmbient,excitation,rate,7,9)}
        assertTrue(lowSnr.message!!.contains("Low SNR: built-in"))
        val refSnr=assertThrows(IllegalArgumentException::class.java){CalibrationAnalysis.measure(phone,reference,phoneAmbient,loudAmbient,excitation,rate,7,9)}
        assertTrue(refSnr.message!!.contains("Low SNR: external"))
        val clipped=reference.copyOf().also { it[refStart]=1.0f }
        val clipError=assertThrows(IllegalArgumentException::class.java){CalibrationAnalysis.measure(phone,clipped,phoneAmbient,refAmbient,excitation,rate,7,9)}
        assertTrue(clipError.message!!.contains("Clipping: external"))
        val clippedPhone=phone.copyOf().also {it[phoneStart]=1.0f}
        val phoneClip=assertThrows(IllegalArgumentException::class.java){CalibrationAnalysis.measure(clippedPhone,reference,phoneAmbient,refAmbient,excitation,rate,7,9)}
        assertTrue(phoneClip.message!!.contains("Clipping: built-in"))
    }

    @Test fun silentReferenceCannotProduceCalibration() {
        val rate=4000;val n=3*rate
        val phaseRandom=Random(456)
        val excitation=Generators.pink(n,rate,20.0,1800.0,0.3,DoubleArray(n/2+1){phaseRandom.nextDouble()*2*PI})
        val phone=FloatArray(12*rate);val reference=FloatArray(12*rate)
        for(i in 0 until 3*n) phone[rate/2+i]=excitation[i%n]
        assertThrows(IllegalArgumentException::class.java){CalibrationAnalysis.measure(phone,reference,FloatArray(rate),FloatArray(rate),excitation,rate,7,9)}
    }

    @Test fun constantReferenceGainDoesNotBecomeFrequencyCorrection() {
        val rate=4000;val n=3*rate
        val random=Random(77)
        val excitation=Generators.pink(n,rate,20.0,1800.0,0.1,DoubleArray(n/2+1){random.nextDouble()*2*PI})
        val phone=FloatArray(12*rate);val reference=FloatArray(12*rate)
        for(i in 0 until 3*n) {phone[rate/2+i]=excitation[i%n];reference[rate/2+i]=5*excitation[i%n]}
        val c=CalibrationAnalysis.measure(phone,reference,FloatArray(rate),FloatArray(rate),excitation,rate,7,9)
        assertEquals(0.0,c.correction(1000.0)!!,0.1)
        assertTrue(c.correctionDb.all { abs(it) < 0.5 })
    }
}
