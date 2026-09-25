package com.bm.spectrum

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GeneratorOutputTest {
    @Test fun pcm16PreservesSignalLevelAndDuplicatesChannels() {
        val input = FloatArray(1024) { (0.9 * sin(2 * PI * it / 48)).toFloat() }
        val output = ShortArray(2048)
        monoToStereoPcm16(input, input.size, output)
        for (i in input.indices) {
            assertEquals(output[2 * i], output[2 * i + 1])
            assertEquals(input[i].toDouble(), output[2 * i] / 32768.0, 0.5 / 32768.0)
        }
        val originalPower = input.sumOf { it.toDouble().pow(2) }
        val convertedPower = input.indices.sumOf { (output[2 * it] / 32768.0).pow(2) }
        assertEquals(0.0, 10 * log10(convertedPower / originalPower), 0.001)
    }

    @Test fun pcm16SaturatesAndHonorsPartialFinalBlock() {
        val input = floatArrayOf(-1.1f, -1f, 0f, 1f, 1.1f, 0.9f)
        val output = ShortArray(12) { 123 }
        monoToStereoPcm16(input, 5, output)
        assertArrayEquals(shortArrayOf(-32768, -32768, -32768, -32768, 0, 0,
            32767, 32767, 32767, 32767, 123, 123), output)
    }
}
