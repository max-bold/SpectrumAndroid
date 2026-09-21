package com.bm.spectrum.dsp

import kotlin.math.*

/** IEC 61260 base-10 center-frequency grid, NOT an IEC filter-bank implementation.
 * Even denominators have a half-step offset about 1 kHz. Include bands whose
 * edges intersect the measurement range so nominal 20 Hz is not dropped.
 * Reference: https://www.nti-audio.com/en/support/know-how/fractional-octave-band-filter
 */
object OctaveBands {
    fun centers(low: Double, high: Double, fraction: Int): DoubleArray {
        require(fraction in listOf(3, 6, 12))
        require(low > 0 && high > low)
        val offset = if (fraction % 2 == 0) 0.5 else 0.0
        val ratio = 10.0.pow(0.3 / (2 * fraction))
        return (-200..200).map { 1000 * 10.0.pow(0.3 * (it + offset) / fraction) }
            .filter { it * ratio > low && it / ratio < high }.toDoubleArray()
    }
}
