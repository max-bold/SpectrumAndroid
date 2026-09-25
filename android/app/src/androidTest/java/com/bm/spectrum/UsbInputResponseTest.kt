package com.bm.spectrum

import android.content.Intent
import android.media.*
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.*

/** Opt-in hardware test: a flat USB interface with Line Out wired to Line In. */
@RunWith(AndroidJUnit4::class)
class UsbInputResponseTest {
    @Test fun usbInputPreservesLowFrequencies() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("usbLoopback") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(AudioManager::class.java)
        assertTrue("Connect the USB loopback interface", manager.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        })
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val rate = 48000
            val frequencies = doubleArrayOf(20.0, 30.0, 50.0, 80.0, 100.0, 150.0, 250.0, 1000.0, 4000.0, 10000.0, 20000.0)
            // Integer-Hz tones and a one-second analysis segment avoid FFT-bin leakage.
            val signal = FloatArray(rate) { n -> frequencies.sumOf { f -> 0.025 * sin(2 * PI * f * n / rate) }.toFloat() }
            val input = MeasurementInput.open(manager, rate)
            var output: GeneratorOutput? = null
            val stop = AtomicBoolean(false)
            val writerFailure = AtomicReference<Throwable?>(null)
            var writer: Thread? = null
            val capture = FloatArray(rate * 3)
            var count = 0
            try {
                val playback = GeneratorOutput.open(manager, rate)
                output = playback
                input.startRecording()
                playback.track.play()
                val deadline = System.nanoTime() + 8_000_000_000L
                writer = thread(name = "USB-response-test") {
                    try {
                        val block = FloatArray(1024)
                        var position = 0
                        while (!stop.get() && System.nanoTime() < deadline) {
                            for (i in block.indices) block[i] = signal[(position + i) % rate]
                            playback.write(block, block.size)
                            position += block.size
                        }
                    } catch (e: Throwable) { if (!stop.get()) writerFailure.set(e) }
                }
                while (count < capture.size && System.nanoTime() < deadline) {
                    val n = input.read(capture, count, min(1024, capture.size - count), AudioRecord.READ_NON_BLOCKING)
                    assertTrue("AudioRecord error: $n", n >= 0)
                    count += n
                    if (n == 0) Thread.sleep(5)
                }
                assertEquals(capture.size, count)
                assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, input.audioSource)
                val routed = input.routedDevice
                assertTrue("Capture must use USB", routed?.type == AudioDeviceInfo.TYPE_USB_DEVICE || routed?.type == AudioDeviceInfo.TYPE_USB_HEADSET)
                assertNull(writerFailure.get())
            } finally {
                stop.set(true)
                try { input.stop() } finally { input.release() }
                writer?.join(2000)
                try { output?.track?.stop(); writer?.join(2000) } finally { output?.release() }
            }
            // Last second excludes startup; estimate amplitudes directly from PCM, before DSP.
            val db = frequencies.map { f ->
                var real = 0.0
                var imaginary = 0.0
                for (n in 0 until rate) {
                    val value = capture[capture.size - rate + n]
                    val phase = 2 * PI * f * n / rate
                    real += value * cos(phase)
                    imaginary += value * sin(phase)
                }
                20 * log10(max(1e-12, 2 * hypot(real, imaginary) / rate))
            }
            assertTrue("Check loopback wiring and media volume", db[7] > -60)
            frequencies.indices.forEach { i ->
                val relative = db[i] - db[7]
                Log.i("BM-USB-response", "${frequencies[i]} Hz: $relative dB relative to 1 kHz")
                assertEquals("USB response at ${frequencies[i]} Hz", 0.0, relative, 1.0)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
