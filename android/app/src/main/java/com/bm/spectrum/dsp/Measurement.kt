package com.bm.spectrum.dsp

import kotlin.math.*

const val GENERATOR_FADE_SECONDS = 0.5

/** Desktop sweep extension, with a phase-continuous frequency ceiling below Nyquist. */
class Sweep(val duration: Double, val rate: Int, val low: Double, val high: Double) {
    val fadeSamples = (GENERATOR_FADE_SECONDS * rate).roundToInt()
    val samples = (duration * rate).roundToInt() + 2 * fadeSamples
    private val slope = ln(high / low) / duration
    val startFrequency = low * exp(-slope * GENERATOR_FADE_SECONDS)
    val ceiling = rate * 0.49
    private val ceilingTime = ln(ceiling / startFrequency) / slope
    fun frequency(time: Double) = min(ceiling, startFrequency * exp(slope * time))
    fun signal(): FloatArray = FloatArray(samples) { i ->
        val time = i.toDouble() / rate
        val exponentialTime = min(time, ceilingTime)
        val cycles = startFrequency / slope * expm1(slope * exponentialTime) + ceiling * max(0.0, time - ceilingTime)
        val gain = min(fadeGain(i, fadeSamples), fadeGain(samples - 1 - i, fadeSamples))
        (0.9 * sin(2 * PI * cycles) * gain).toFloat()
    }
}

fun fadeGain(position: Int, length: Int): Double = (position.toDouble() / max(1, length - 1)).coerceIn(0.0, 1.0)

data class MeasurementPlot(val frequency: DoubleArray, val db: DoubleArray, val elapsed: Double,
                           val frames: Int, val ms: Double, val kind: String, val fftSize: Int)

/** Welch is a preview only. Spectrum always retains PCM for a final full-recording periodogram. */
class Measurement(private val settings: Settings, private val rate: Int, private val cache: PlanCache,
                  private val emit: (MeasurementPlot) -> Unit) {
    val total = ((settings.duration + if (settings.mode == "Spectrum" && settings.generatorEnabled) 2 * GENERATOR_FADE_SECONDS else 0.0) * rate).roundToInt()
    private val spectrum = settings.mode == "Spectrum"
    private val finalKey = PlanKey(total, rate, settings.low, settings.high, settings.spectrumPoints, settings.smoothing, false)
    private val streamSize = if (!spectrum) (settings.rtaWidth * rate).roundToInt() else settings.welchSize
    private val streamKey = PlanKey(streamSize, rate, settings.low, settings.high, settings.points(), settings.width(),
        if (spectrum) true else !settings.generatorEnabled, if (spectrum) 0 else settings.rtaFraction)
    private val streamAnalyzer = if (!spectrum || settings.onlineWelch) Analyzer(streamKey, cache) else null
    private val finalAnalyzer = if (spectrum) Analyzer(finalKey, cache) else null
    private val recording = if (spectrum) DoubleArray(total) else null
    private val average = PowerAverage(settings.points())
    private var lastPower: DoubleArray? = null
    private var lastMs = 0.0
    private var lastPublish = 0L
    var samples = 0; private set
    var frames = 0; private set
    val elapsed get() = samples.toDouble() / rate
    private val stream = streamAnalyzer?.let { analyzer ->
        val hop = if (spectrum) settings.welchHop else (settings.rtaHop * rate).roundToInt()
        FrameStream(streamSize, hop) { frame ->
            val start = System.nanoTime()
            val power = analyzer.analyze(frame)
            lastPower = if (spectrum) average.add(power) else power
            lastMs = (System.nanoTime() - start) / 1e6
            frames++
            if (System.nanoTime() - lastPublish >= 80_000_000L) { publish(); lastPublish = System.nanoTime() }
        }
    }
    fun push(block: FloatArray, count: Int) {
        require(count in 0..block.size)
        recording?.let { require(samples + count <= it.size); for (i in 0 until count) it[samples+i] = block[i].toDouble() }
        samples += count
        stream?.push(block, count)
    }
    /** RTA uses a recorder-owned rolling window so slow analysis skips old frames. */
    fun pushLatest(snapshot: LatestWindow.Snapshot) {
        require(!spectrum)
        val start = System.nanoTime()
        val power = streamAnalyzer!!.analyze(snapshot.pcm)
        lastPower = power
        lastMs = (System.nanoTime() - start) / 1e6
        samples = snapshot.captured.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        frames++
        publish()
    }
    private fun publish() {
        lastPower?.let { output(streamAnalyzer!!, it, if (spectrum) "welch" else "rta", lastMs) }
    }
    private fun output(analyzer: Analyzer, power: DoubleArray, kind: String, ms: Double) {
        emit(MeasurementPlot(analyzer.plan.frequencies, DoubleArray(power.size) { 10 * log10(power[it].coerceAtLeast(1e-20)) }, elapsed, frames, ms, kind, analyzer.key.size))
    }
    fun finish() {
        if (recording == null) { publish(); return }
        if (samples < 2) return
        val analyzer = if (samples == total) finalAnalyzer!! else Analyzer(finalKey.copy(size = samples), cache)
        val start = System.nanoTime()
        val power = analyzer.analyze(if (samples == total) recording else recording.copyOf(samples))
        frames = 1
        output(analyzer, power, "periodogram", (System.nanoTime() - start) / 1e6)
    }
}
