package com.bm.spectrum.dsp

import kotlin.math.*

data class Settings(
    val mode: String = "RTA",
    val low: Double = 20.0,
    val high: Double = 20000.0,
    val duration: Double = 5.0,
    val smoothing: Double = 0.3,
    val spectrumPoints: Int = 256,
    val onlineWelch: Boolean = true,
    val welchSize: Int = 8192,
    val welchHop: Int = 4096,
    val rtaWidth: Double = 3.0,
    val rtaHop: Double = 0.1,
    val rtaFraction: Int = 3,
    val generatorEnabled: Boolean = false
) {
    fun validate(sampleRate: Int = 48000) {
        require(mode in listOf("RTA", "Spectrum")) { "Unknown mode" }
        require(low.isFinite() && high.isFinite() && low >= 20 && high <= 20000 && high > low && high < sampleRate / 2) { "Band: 20–20000 Hz; low must be below high" }
        require(duration.isFinite() && duration in 0.5..30.0) { "Duration: 0.5–30 s" }
        require(smoothing.isFinite() && smoothing in 0.1..1.0) { "Smoothing: 0.1–1 oct" }
        require(spectrumPoints in 32..1024) { "Spectrum: 32–1024 points" }
        require(welchSize in 1024..262144 && welchSize and (welchSize - 1) == 0) { "Welch size: power of two, 1024–262144" }
        require(welchHop in 1..welchSize) { "Welch hop: 1–window size" }
        require(rtaWidth.isFinite() && rtaWidth in 0.1..10.0) { "RTA window: 0.1–10 s" }
        require(rtaHop.isFinite() && rtaHop in 0.02..rtaWidth) { "RTA hop: 0.02 s–window width" }
        require(rtaFraction in listOf(3, 6, 12, 512, 1024)) { "RTA: 1/3, 1/6, 1/12, 512/0.3 or 1024/0.15" }
        if (mode == "Spectrum" && onlineWelch) require(welchSize <= (duration * sampleRate).roundToInt()) { "Welch window exceeds recording duration" }
    }
    // The two dense presets reuse the stored selector but use logarithmic point grids.
    fun rtaOctaveFraction() = if (rtaFraction in listOf(3, 6, 12)) rtaFraction else 0
    fun points() = if (mode != "RTA") spectrumPoints else if (rtaOctaveFraction() > 0)
        OctaveBands.centers(low, high, rtaFraction).size else rtaFraction
    fun width() = if (mode != "RTA") smoothing else when (rtaFraction) {
        512 -> 0.3
        1024 -> 0.15
        else -> log2(10.0.pow(0.3 / rtaFraction))
    }
}
