package com.bm.spectrum

import android.media.*
import android.util.Log
import com.bm.spectrum.dsp.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.*

class AudioEngine(private val changed: () -> Unit, private val plot: (MeasurementPlot) -> Unit) {
    val rate = 48000
    val cache = PlanCache()
    @Volatile var running = false; private set
    @Volatile var generating = false; private set
    @Volatile var error: String? = null; private set
    @Volatile var elapsed = 0.0; private set
    @Volatile var sweepLead = 0.0; private set
    @Volatile var frames = 0; private set
    @Volatile private var session: Session? = null

    private class Session {
        val stop = AtomicBoolean(false)
        val done = AtomicBoolean(false)
        val queue = ArrayBlockingQueue<Pair<FloatArray, Int>>(128)
        val pool = ArrayBlockingQueue<FloatArray>(130).apply { repeat(130) { add(FloatArray(1024)) } }
        @Volatile var record: AudioRecord? = null
        var reader: Thread? = null
        var processor: Thread? = null
        var writer: Thread? = null
        @Volatile var track: AudioTrack? = null
    }

    @Synchronized fun start(settings: Settings) {
        settings.validate(rate)
        check(!running) { "A measurement is already running" }
        stopMeasurement()
        error = null; elapsed = 0.0; frames = 0
        sweepLead = if (settings.mode == "Spectrum" && settings.generatorEnabled) GENERATOR_FADE_SECONDS else 0.0
        // Prepare both Welch and full-recording plans before opening the microphone.
        val measurement = Measurement(settings, rate, cache, plot)
        val total = measurement.total
        val s = Session()
        val period = if (!settings.generatorEnabled) null else if (settings.mode == "RTA")
            Generators.pink((settings.rtaWidth * rate).roundToInt(), rate, settings.low, settings.high, 0.9)
        else Sweep(settings.duration, rate, settings.low, settings.high).signal()
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        check(minimum > 0) { "Recording format is unavailable" }
        // Both modes request the same raw capture path; vendor processing may remain
        // on devices that do not declare UNPROCESSED support.
        val recorder = try { AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(max(minimum * 4, rate * 4)).build()
        } catch (e: SecurityException) {
            throw IllegalStateException("Microphone access was revoked. Enable it in Android settings.", e)
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) { recorder.release(); error("Microphone is unavailable") }
        try {
            if (period != null) {
                val buffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                check(buffer > 0) { "Playback format is unavailable" }
                s.track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(max(buffer * 2, 8192)).setTransferMode(AudioTrack.MODE_STREAM).build()
                check(s.track!!.state == AudioTrack.STATE_INITIALIZED) { "Audio output is unavailable" }
            }
            recorder.startRecording()
            s.track?.play()
        } catch (e: Exception) { recorder.release(); s.track?.release(); throw e }
        s.record = recorder; session = s; running = true; generating = period != null; changed()
        if (period != null) s.writer = thread(name = "BM-generator") {
            val output = s.track!!
            try {
                val fade = (GENERATOR_FADE_SECONDS * rate).roundToInt()
                val block = FloatArray(1024)
                var position = 0
                var stoppingAt: Int? = null
                var written = 0L
                while (true) {
                    if (s.stop.get() && stoppingAt == null) stoppingAt = position
                    val remaining = stoppingAt?.let { fade - (position - it) } ?: Int.MAX_VALUE
                    val available = if (settings.mode == "Spectrum") period.size - position else Int.MAX_VALUE
                    val count = min(block.size, min(remaining, available))
                    if (count <= 0) break
                    for (i in 0 until count) {
                        val p = position + i
                        var gain = if (settings.mode == "RTA") fadeGain(p, fade) else 1.0
                        stoppingAt?.let { gain *= 1 - fadeGain(p - it, fade) }
                        block[i] = (period[p % period.size] * gain).toFloat()
                    }
                    var offset = 0
                    while (offset < count) {
                        val n = output.write(block, offset, count - offset, AudioTrack.WRITE_BLOCKING)
                        check(n > 0) { "AudioTrack error: $n" }
                        offset += n; written += n
                    }
                    position += count
                }
                // Drain the queued fade instead of flushing it away on stop.
                val deadline = System.nanoTime() + 2_000_000_000L
                while ((output.playbackHeadPosition.toLong() and 0xffffffffL) < written && System.nanoTime() < deadline) Thread.sleep(5)
            } catch (e: Exception) {
                if (!s.stop.get()) { fail(e.message ?: "Generator failed"); signalStop(s) }
            } finally {
                try { output.pause(); output.flush(); output.stop() } catch (_: Exception) { }
                output.release(); s.track = null; generating = false; changed()
            }
        }
        s.processor = thread(name = "BM-analysis") {
            try {
                var lastStatus = 0L
                while (!s.done.get() || s.queue.isNotEmpty()) {
                    val (block, count) = s.queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    measurement.push(block, count)
                    elapsed = measurement.elapsed; frames = measurement.frames
                    s.pool.offer(block)
                    if (System.nanoTime() - lastStatus > 200_000_000L) { changed(); lastStatus = System.nanoTime() }
                }
                measurement.finish()
                frames = measurement.frames
            } catch (e: Exception) { fail(e.message ?: "Analysis failed") }
            finally {
                signalStop(s)
                s.reader?.join()
                s.writer?.join()
                running = false; generating = false; changed()
                Log.i("BM-Spectrum", "Finished: frames=$frames seconds=$elapsed plans=${cache.builds}")
            }
        }
        s.reader = thread(name = "BM-record") {
            var captured = 0
            try {
                while (!s.stop.get() && (settings.mode == "RTA" || captured < total)) {
                    val block = s.pool.poll() ?: error("Analysis cannot keep up with recording")
                    val wanted = if (settings.mode == "Spectrum") min(block.size, total-captured) else block.size
                    val count = recorder.read(block, 0, wanted, AudioRecord.READ_BLOCKING)
                    if (count < 0) { s.pool.offer(block); if (!s.stop.get()) error("AudioRecord error: $count"); break }
                    if (count == 0) { s.pool.offer(block); continue }
                    captured += count
                    if (!s.queue.offer(block to count)) { s.pool.offer(block); error("Analysis cannot keep up with recording; increase hop") }
                }
            } catch (e: Exception) { if (!s.stop.get()) fail(e.message ?: "Recording failed") }
            finally {
                try { recorder.stop() } catch (_: Exception) { }
                recorder.release(); s.record = null; s.done.set(true)
                s.stop.set(true)
            }
        }
    }

    private fun signalStop(s: Session) {
        s.stop.set(true)
        try { s.record?.stop() } catch (_: Exception) { }
        // Playback observes stop and completes its fade-out before releasing the track.
    }

    @Synchronized fun stopMeasurement() {
        val s = session ?: return
        signalStop(s)
        s.reader?.join()
        s.writer?.join()
        s.processor?.join()
        session = null; running = false; generating = false; changed()
    }

    fun fail(message: String) { error = message; Log.e("BM-Spectrum", message); changed() }
    fun stopAll() = stopMeasurement()
}
