package com.bm.spectrum.dsp

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.*

object CalibrationLimits {
    const val MIN_FREQ = 20.0
    const val MAX_FREQ = 20000.0
    const val MIN_SNR_DB = 20.0
    const val MIN_REPEATABILITY = 0.9
    const val MAX_CORRECTION_DB = 20.0
    const val POINTS = 128
    const val WIDTH_OCTAVES = 1.0
    const val PERIOD_SECONDS = 3
    const val SILENCE_SECONDS = 0.5
    const val AMBIENT_SECONDS = 3
    const val FADE_SECONDS = 0.2
}

/** Extend the periodic signal so all three analyzed periods retain unity gain. */
fun calibrationPlayback(period: FloatArray, rate: Int): FloatArray {
    val fade = (CalibrationLimits.FADE_SECONDS * rate).roundToInt()
    return FloatArray(3 * period.size + 2 * fade) { i ->
        val phase = Math.floorMod(i - fade, period.size)
        val distance = min(i, 3 * period.size + 2 * fade - 1 - i)
        val gain = if (distance >= fade) 1.0 else 0.5 - 0.5 * cos(PI * distance / fade)
        (period[phase] * gain).toFloat()
    }
}

fun sourceSnrDb(signalAndNoise: Double, noise: Double): Double {
    val floor=noise.coerceAtLeast(1e-30)
    return 10*log10(max(signalAndNoise-floor,1e-30)/floor)
}

fun calibrationSignalPsdScale(rate: Int, period: Int): Double = 2.0 / (3.0 * rate * period * 0.375)

fun periodRepeatability(powers: DoubleArray): Double {
    val mean = powers.average()
    if (mean <= 0.0) return 0.0
    val variance = powers.sumOf { (it - mean).pow(2) } / powers.size
    return 1.0 / (1.0 + variance / (mean * mean))
}

fun calibrationBinValid(frequency: Double, rate: Int, phoneSnrDb: Double, referenceSnrDb: Double, repeatability: Double): Boolean =
    frequency >= CalibrationLimits.MIN_FREQ && frequency <= min(CalibrationLimits.MAX_FREQ,rate/2.0) &&
        phoneSnrDb > CalibrationLimits.MIN_SNR_DB && referenceSnrDb > CalibrationLimits.MIN_SNR_DB &&
        repeatability >= CalibrationLimits.MIN_REPEATABILITY

data class CalibrationSpectra(val frequencies: DoubleArray, val phoneNoiseDb: DoubleArray,
    val referenceNoiseDb: DoubleArray, val phoneSignalDb: DoubleArray, val referenceSignalDb: DoubleArray)

data class CalibrationCurve(
    val inputDeviceId: Int, val sampleRate: Int, val audioSource: Int,
    val frequencies: DoubleArray, val correctionDb: DoubleArray,
    val fMin: Double, val fMax: Double, val sensitivityDbSpl: Double? = null,
    val averageRepeatability: Double = 0.0, val phoneSnrDb: Double = 0.0,
    val referenceSnrDb: Double = 0.0, val responseDb: DoubleArray = DoubleArray(0),
    val spectra: CalibrationSpectra? = null
) {
    fun validate() {
        require(sampleRate > 0 && frequencies.size == CalibrationLimits.POINTS && correctionDb.size == frequencies.size)
        require(fMin >= CalibrationLimits.MIN_FREQ && fMax <= min(CalibrationLimits.MAX_FREQ, sampleRate / 2.0) && fMin < fMax)
        require(frequencies.first() >= fMin && frequencies.last() <= fMax)
        require(frequencies.indices.all { i -> frequencies[i].isFinite() && correctionDb[i].isFinite() && abs(correctionDb[i]) <= CalibrationLimits.MAX_CORRECTION_DB && (i == 0 || frequencies[i] > frequencies[i-1]) })
        require((responseDb.isEmpty() || responseDb.size == frequencies.size) && responseDb.all { it.isFinite() })
    }
    fun matches(id: Int, rate: Int, source: Int) = inputDeviceId == id && sampleRate == rate && audioSource == source
    fun correction(frequency: Double): Double? {
        if (frequency < fMin || frequency > fMax) return null
        val index = frequencies.binarySearch(frequency)
        if (index >= 0) return correctionDb[index]
        val right = (-index - 1).coerceIn(1, frequencies.lastIndex)
        val left = right - 1
        val t = ln(frequency / frequencies[left]) / ln(frequencies[right] / frequencies[left])
        return correctionDb[left] + t * (correctionDb[right] - correctionDb[left])
    }
    fun restricted(low: Double, high: Double): Pair<Double, Double> {
        val a = max(low, fMin); val b = min(high, fMax)
        require(a < b) { "Measurement band does not overlap calibration range" }
        return a to b
    }
}

/** Reuses the analyzer's log-Gaussian kernel, without its de-pink weighting. */
fun gaussianLogWeight(frequency: Double, center: Double, width: Double): Double =
    exp(-4 * ln(2.0) * (log2(frequency / center) / width).pow(2)) / frequency

object CalibrationAnalysis {
    private fun transform(samples: FloatArray, offset: Int, size: Int): DoubleArray {
        val work = DoubleArray(2 * size)
        val mean = (offset until offset + size).sumOf { samples[it].toDouble() } / size
        for (i in 0 until size) work[i] = (samples[offset+i] - mean) * (0.5 - 0.5*cos(2*PI*i/size))
        DoubleFFT_1D(size.toLong()).realForwardFull(work)
        return work
    }
    private fun power(fft: DoubleArray, k: Int) = fft[2*k].pow(2) + fft[2*k+1].pow(2)
    private fun noise(samples: FloatArray, rate: Int, channel: String): DoubleArray {
        val size = (CalibrationLimits.SILENCE_SECONDS * rate).roundToInt()
        require(samples.size >= 2 * size) { "Incomplete background-noise recording" }
        require(samples.none { abs(it) >= 0.99f }) { "Clipping: $channel microphone background noise. Reduce input gain and repeat calibration." }
        val scale = 2.0 / (rate * size * 0.375)
        val frames = samples.size / size
        val result = DoubleArray(size/2+1)
        for (frame in 0 until frames) {
            val fft = transform(samples, frame * size, size)
            for (k in result.indices) result[k] += power(fft,k) * scale / frames
        }
        return result
    }
    /** FFT correlation with one known second. A constant channel delay is allowed. */
    fun align(samples: FloatArray, excitation: FloatArray, rate: Int): Int {
        val templateSize = rate
        val scan = min(samples.size - templateSize, 2 * rate)
        require(scan > 0 && excitation.size >= templateSize)
        var size = 1
        while (size < scan + templateSize) size *= 2
        val a = DoubleArray(2*size)
        val b = DoubleArray(2*size)
        for (i in 0 until scan + templateSize) a[2*i] = samples[i].toDouble()
        for (i in 0 until templateSize) b[2*i] = excitation[i].toDouble()
        val fft = DoubleFFT_1D(size.toLong())
        fft.complexForward(a); fft.complexForward(b)
        for (i in 0 until size) {
            val ar=a[2*i]; val ai=a[2*i+1]; val br=b[2*i]; val bi=b[2*i+1]
            a[2*i]=ar*br+ai*bi; a[2*i+1]=ai*br-ar*bi
        }
        fft.complexInverse(a,true)
        var best = 0; var peak = 0.0
        for (i in 0..scan) if (abs(a[2*i]) > peak) { peak=abs(a[2*i]); best=i }
        require(peak > 1e-5) { "Calibration signal was not captured" }
        return best
    }
    fun measure(phone: FloatArray, reference: FloatArray, phoneAmbient: FloatArray,
                referenceAmbient: FloatArray, excitation: FloatArray, rate: Int,
                inputDeviceId: Int, audioSource: Int): CalibrationCurve {
        val period = rate * CalibrationLimits.PERIOD_SECONDS
        require(excitation.size == period)
        require(phone.none { abs(it) >= 0.99f }) { "Clipping: built-in microphone signal. Reduce playback level and repeat calibration." }
        require(reference.none { abs(it) >= 0.99f }) { "Clipping: external microphone signal. Reduce its input gain and repeat calibration." }
        val pn = noise(phoneAmbient, rate, "built-in")
        val rn = noise(referenceAmbient, rate, "external")
        require(phone.any { abs(it) > 1e-7f }) { "Low SNR: built-in microphone. No calibration signal detected." }
        require(reference.any { abs(it) > 1e-7f }) { "Low SNR: external microphone. No calibration signal detected." }
        val phoneStart = align(phone, excitation, rate)
        val refStart = align(reference, excitation, rate)
        require(phoneStart >= rate/4 && refStart >= rate/4 && phoneStart + 3*period + rate/2 <= phone.size && refStart + 3*period + rate/2 <= reference.size) { "Incomplete calibration capture" }
        val noiseSize = (CalibrationLimits.SILENCE_SECONDS * rate).roundToInt()
        val bins = period/2+1
        val pp=DoubleArray(bins); val rr=DoubleArray(bins)
        val phonePeriods=Array(3) { DoubleArray(bins) }
        val referencePeriods=Array(3) { DoubleArray(bins) }
        for (j in 0..2) {
            val p=transform(phone,phoneStart+j*period,period)
            val r=transform(reference,refStart+j*period,period)
            for (k in 0 until bins) {
                val pr=p[2*k];val pi=p[2*k+1];val qr=r[2*k];val qi=r[2*k+1]
                phonePeriods[j][k]=pr*pr+pi*pi
                referencePeriods[j][k]=qr*qr+qi*qi
                pp[k]+=phonePeriods[j][k];rr[k]+=referencePeriods[j][k]
            }
        }
        val norm=calibrationSignalPsdScale(rate,period)
        for (k in 0 until bins) {pp[k]*=norm;rr[k]*=norm}
        val low=CalibrationLimits.MIN_FREQ; val high=min(CalibrationLimits.MAX_FREQ,rate/2.0)
        val grid=DoubleArray(CalibrationLimits.POINTS) { low*(high/low).pow(it.toDouble()/(CalibrationLimits.POINTS-1)) }
        val raw=DoubleArray(grid.size);val repeatability=DoubleArray(grid.size);val pSnr=DoubleArray(grid.size);val rSnr=DoubleArray(grid.size)
        val valid=BooleanArray(grid.size)
        val phoneNoiseDb=DoubleArray(grid.size);val referenceNoiseDb=DoubleArray(grid.size)
        val phoneSignalDb=DoubleArray(grid.size);val referenceSignalDb=DoubleArray(grid.size)
        for (i in grid.indices) {
            val center=grid[i]
            val radius=2.0.pow(CalibrationLimits.WIDTH_OCTAVES/2)
            val first=max(1,ceil(center/radius*period/rate).toInt())
            val last=min(bins-1,floor(center*radius*period/rate).toInt())
            var weight=0.0;var gain=0.0;var snrp=0.0;var snrr=0.0
            var signalP=0.0;var signalR=0.0;var noiseP=0.0;var noiseR=0.0
            val bandPhone=DoubleArray(3);val bandReference=DoubleArray(3)
            for (k in first..last) {
                val f=k.toDouble()*rate/period
                val w=gaussianLogWeight(f,center,CalibrationLimits.WIDTH_OCTAVES)
                val cp=pp[k].coerceAtLeast(1e-30);val rp=rr[k].coerceAtLeast(1e-30)
                val nk=(f*noiseSize/rate).roundToInt().coerceIn(0,pn.lastIndex)
                val np=pn[nk].coerceAtLeast(1e-30);val nr=rn[nk].coerceAtLeast(1e-30)
                signalP+=w*cp;signalR+=w*rp;noiseP+=w*np;noiseR+=w*nr
                gain+=w*10*log10(rp/cp)
                snrp+=w*sourceSnrDb(cp,np);snrr+=w*sourceSnrDb(rp,nr);weight+=w
                for(j in 0..2) {bandPhone[j]+=w*phonePeriods[j][k];bandReference[j]+=w*referencePeriods[j][k]}
            }
            if (weight > 0) {
                raw[i]=gain/weight;pSnr[i]=snrp/weight;rSnr[i]=snrr/weight
                repeatability[i]=min(periodRepeatability(bandPhone),periodRepeatability(bandReference))
                phoneSignalDb[i]=10*log10((signalP/weight).coerceAtLeast(1e-20))
                referenceSignalDb[i]=10*log10((signalR/weight).coerceAtLeast(1e-20))
                phoneNoiseDb[i]=10*log10((noiseP/weight).coerceAtLeast(1e-20))
                referenceNoiseDb[i]=10*log10((noiseR/weight).coerceAtLeast(1e-20))
            }
            valid[i]=weight>0 && calibrationBinValid(center,rate,pSnr[i],rSnr[i],repeatability[i])
        }
        // Fill isolated failures, then require a sustained region of at least 12 grid points.
        for (i in 2 until valid.size-2) if (!valid[i] && valid[i-1] && valid[i+1]) valid[i]=true
        var bestStart=-1;var bestEnd=-1;var run=-1
        for (i in 0..valid.size) {
            if (i<valid.size && valid[i]) {if(run<0)run=i}
            else if(run>=0) {if(i-run>bestEnd-bestStart) {bestStart=run;bestEnd=i-1};run=-1}
        }
        require(bestStart>=0 && bestEnd-bestStart>=11) {
            val channels=listOfNotNull(
                if(pSnr.count { it>CalibrationLimits.MIN_SNR_DB }<12) "built-in" else null,
                if(rSnr.count { it>CalibrationLimits.MIN_SNR_DB }<12) "external" else null)
            if(channels.isNotEmpty()) "Low SNR: ${channels.joinToString(" and ")} microphone. Signal must exceed background noise by 20 dB over a continuous band."
            else if(pSnr.indices.count { pSnr[it]>20 && rSnr[it]>20 }<12)
                "Low SNR: built-in and external microphones have no shared reliable band. Reduce background noise and repeat calibration."
            else "No stable calibration range. Keep both microphones and the speaker stationary and repeat calibration."
        }
        val fMin=grid[bestStart];val fMax=grid[bestEnd]
        val frequencies=DoubleArray(CalibrationLimits.POINTS) {
            when(it) {0 -> fMin; CalibrationLimits.POINTS-1 -> fMax
                else -> fMin*(fMax/fMin).pow(it.toDouble()/(CalibrationLimits.POINTS-1))}
        }
        fun transferAt(f: Double): Double {
            val pos=grid.binarySearch(f).let { if(it>=0) it else (-it-1).coerceIn(1,grid.lastIndex) }
            return if(pos==0)raw[0] else {
                val t=ln(f/grid[pos-1])/ln(grid[pos]/grid[pos-1]);raw[pos-1]*(1-t)+raw[pos]*t
            }
        }
        // The external microphone's input gain is unrelated to its frequency response.
        val levelOffset=transferAt(1000.0.coerceIn(fMin,fMax))
        val response=DoubleArray(frequencies.size) { levelOffset-transferAt(frequencies[it]) }
        val correction=DoubleArray(frequencies.size) { (-response[it]).coerceIn(-CalibrationLimits.MAX_CORRECTION_DB,CalibrationLimits.MAX_CORRECTION_DB) }
        return CalibrationCurve(inputDeviceId,rate,audioSource,frequencies,correction,fMin,fMax,
            averageRepeatability=repeatability.slice(bestStart..bestEnd).average(),phoneSnrDb=pSnr.slice(bestStart..bestEnd).average(),referenceSnrDb=rSnr.slice(bestStart..bestEnd).average(),responseDb=response,
            spectra=CalibrationSpectra(grid,phoneNoiseDb,referenceNoiseDb,phoneSignalDb,referenceSignalDb)).also {it.validate()}
    }
}
