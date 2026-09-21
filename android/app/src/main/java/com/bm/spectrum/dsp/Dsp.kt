package com.bm.spectrum.dsp

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.*
import java.util.LinkedHashMap
import java.util.Random

/** All powers are one-sided PSD. De-pink is P*f, before Gaussian smoothing.
 * Gaussian FWHM, -30 dB support and 1/f Jacobian match BM Spectrum. */
data class PlanKey(val size: Int, val sampleRate: Int, val low: Double, val high: Double,
                   val points: Int, val width: Double, val hann: Boolean, val octaveFraction: Int = 0)

class GaussianPlan(val key: PlanKey) {
    val frequencies = if (key.octaveFraction > 0) OctaveBands.centers(key.low, key.high, key.octaveFraction)
        else DoubleArray(key.points) { key.low * (key.high / key.low).pow(it.toDouble() / (key.points - 1)) }
    private val starts = IntArray(key.points)
    private val weights: Array<DoubleArray>?
    private val ends = IntArray(key.points)
    val bytes: Long
    init {
        require(frequencies.size == key.points)
        val df = key.sampleRate.toDouble() / key.size
        val radius = key.width / (2 * sqrt(2 * ln(2.0))) * sqrt(-2 * ln(0.001))
        // BM Spectrum trims the input to the requested band before smoothing.
        val first = max(1, ceil(key.low / df).toInt())
        val last = min(key.size / 2 + 1, floor(key.high / df).toInt() + 1)
        for (p in frequencies.indices) {
            val center = frequencies[p]
            var a = ceil(center / 2.0.pow(radius) / df).toInt()
            var b = ceil(center * 2.0.pow(radius) / df).toInt()
            if (b <= a) { a = Math.rint(center / df).toInt(); b = a + 1 }
            starts[p] = a.coerceIn(first, max(first, last))
            ends[p] = b.coerceIn(starts[p], max(starts[p], last))
        }
        val weightBytes = ends.indices.sumOf { (ends[it] - starts[it]).toLong() * 8 }
        // Large full-recording periodograms are evaluated row by row. Keep the exact
        // kernel without allocating a huge matrix; streaming RTA/Welch still cache weights.
        val streamed = weightBytes > 48L * 1024 * 1024 && !key.hann && key.octaveFraction == 0
        require(streamed || weightBytes <= 48L * 1024 * 1024) { "Smoothing plan is too large. Reduce point count, smoothing width or duration." }
        bytes = if (streamed) 0 else weightBytes
        weights = if (streamed) null else Array(key.points) { p ->
            val a = starts[p]
            val row = DoubleArray(ends[p] - a)
            var total = 0.0
            for (i in row.indices) {
                val f = (a + i) * df
                val w = exp(-4 * ln(2.0) * (log2(f / frequencies[p]) / key.width).pow(2)) / f
                row[i] = w
                total += w
            }
            // Fuse de-pink into cached weights; no logarithms/exponentials per frame.
            if (total > 0) for (i in row.indices) row[i] = row[i] / total * ((a + i) * df)
            row
        }
    }
    fun apply(power: DoubleArray, out: DoubleArray) {
        val cached = weights
        if (cached == null) {
            val df = key.sampleRate.toDouble() / key.size
            for (p in frequencies.indices) {
                var numerator = 0.0
                var denominator = 0.0
                for (k in starts[p] until ends[p]) {
                    val f = k * df
                    val g = exp(-4 * ln(2.0) * (log2(f / frequencies[p]) / key.width).pow(2))
                    numerator += g * power[k]
                    denominator += g / f
                }
                out[p] = if (denominator > 0) numerator / denominator else 0.0
            }
            return
        }
        for (p in cached.indices) {
            var sum = 0.0
            val row = cached[p]
            val start = starts[p]
            for (i in row.indices) sum += row[i] * power[start + i]
            out[p] = sum
        }
    }
}

/** Bounded LRU shared across measurements. No PCM buffers retained here. */
class PlanCache(private val maxBytes: Long = 64L * 1024 * 1024) {
    private val plans = LinkedHashMap<PlanKey, GaussianPlan>(4, 0.75f, true)
    var builds = 0; private set
    @Synchronized fun get(key: PlanKey): GaussianPlan {
        plans[key]?.let { return it }
        val plan = GaussianPlan(key)
        require(plan.bytes <= maxBytes)
        while (plans.isNotEmpty() && (plans.values.sumOf { it.bytes } + plan.bytes > maxBytes || plans.size >= 4)) {
            plans.remove(plans.entries.first().key)
        }
        plans[key] = plan
        builds++
        return plan
    }
}

/** One instance per stream: FFT plan, Hann and scratch buffers are reused. */
class Analyzer(val key: PlanKey, cache: PlanCache) {
    val plan = cache.get(key)
    private val fft = DoubleFFT_1D(key.size.toLong())
    private val work = DoubleArray(2 * key.size)
    private val temporal = DoubleArray(key.size) { if (key.hann) 0.5 - 0.5 * cos(2 * PI * it / key.size) else 1.0 }
    private val norm = 1.0 / (key.sampleRate * temporal.sumOf { it * it })
    private val psd = DoubleArray(key.size / 2 + 1)
    val power = DoubleArray(key.points)
    fun analyze(samples: DoubleArray): DoubleArray {
        require(samples.size == key.size)
        val mean = samples.average()
        for (i in samples.indices) work[i] = (samples[i] - mean) * temporal[i]
        fft.realForwardFull(work)
        for (k in psd.indices) {
            val factor = if (k == 0 || (key.size % 2 == 0 && k == key.size / 2)) 1.0 else 2.0
            psd[k] = (work[2*k] * work[2*k] + work[2*k+1] * work[2*k+1]) * norm * factor
        }
        plan.apply(psd, power)
        return power
    }
}

/** Exact sample-index framing independent of AudioRecord chunk boundaries. */
class FrameStream(val size: Int, val hop: Int, private val onFrame: (DoubleArray) -> Unit) {
    private val ring = DoubleArray(size)
    private val frame = DoubleArray(size)
    private var write = 0
    var samples = 0L; private set
    var frames = 0; private set
    init { require(size > 1 && hop in 1..size) }
    fun push(input: FloatArray, count: Int) {
        require(count in 0..input.size)
        for (i in 0 until count) {
            ring[write] = input[i].toDouble()
            write = (write + 1) % size
            samples++
            if (samples >= size && (samples - size) % hop == 0L) {
                val tail = size - write
                ring.copyInto(frame, 0, write, size)
                ring.copyInto(frame, tail, 0, write)
                frames++
                onFrame(frame)
            }
        }
    }
}

class PowerAverage(points: Int) {
    val power = DoubleArray(points)
    var count = 0; private set
    fun add(frame: DoubleArray): DoubleArray {
        count++
        for (i in power.indices) power[i] += (frame[i] - power[i]) / count
        return power
    }
}

object Generators {
    fun chirp(n: Int, rate: Int, low: Double, high: Double, amplitude: Double): FloatArray {
        val duration = n.toDouble() / rate
        val l = ln(high / low)
        val data = DoubleArray(n) { sin(2 * PI * low * duration / l * expm1(it.toDouble() / rate / duration * l)) }
        return normalize(data, amplitude)
    }
    fun pink(n: Int, rate: Int, low: Double, high: Double, amplitude: Double, phases: DoubleArray? = null): FloatArray {
        val random = Random()
        val spectrum = DoubleArray(2*n)
        val edge = (10.0.pow(0.5/10) - 1).pow(1.0/8)
        for (k in 1..n/2) {
            val f = k.toDouble() * rate / n
            val a = 1 / sqrt(f) / sqrt(1 + (low*edge/f).pow(8)) / sqrt(1 + (f/(high/edge)).pow(8))
            val phase = phases?.get(k) ?: (random.nextDouble()*2*PI)
            if (n % 2 == 0 && k == n/2) spectrum[2*k] = if (phase < PI) a else -a
            else {
                spectrum[2*k] = a*cos(phase); spectrum[2*k+1] = a*sin(phase)
                spectrum[2*(n-k)] = spectrum[2*k]; spectrum[2*(n-k)+1] = -spectrum[2*k+1]
            }
        }
        DoubleFFT_1D(n.toLong()).complexInverse(spectrum, true)
        return normalize(DoubleArray(n) { spectrum[2*it] }, amplitude)
    }
    private fun normalize(data: DoubleArray, amplitude: Double): FloatArray {
        val peak = data.maxOf { abs(it) }.coerceAtLeast(1e-30)
        return FloatArray(data.size) { (data[it] / peak * amplitude).toFloat() }
    }
}
