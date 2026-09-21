package com.bm.spectrum

import android.media.*
import android.util.Log
import com.bm.spectrum.dsp.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.*

class AudioEngine(private val changed: () -> Unit, private val plot: (DoubleArray, DoubleArray, Double, Int, Double) -> Unit) {
    val rate = 48000
    val cache = PlanCache()
    @Volatile var running = false; private set
    @Volatile var generating = false; private set
    @Volatile var error: String? = null; private set
    @Volatile var elapsed = 0.0; private set
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
        val total = (settings.duration * rate).roundToInt()
        val size = if (settings.mode == "RTA") (settings.rtaWidth * rate).roundToInt() else if (settings.onlineWelch) settings.welchSize else total
        val key = PlanKey(size, rate, settings.low, settings.high, settings.points(), settings.width(), settings.mode == "RTA" || settings.onlineWelch, if (settings.mode == "RTA") settings.rtaFraction else 0)
        // All expensive plans are built before opening the microphone.
        val analyzer = Analyzer(key, cache)
        val s = Session()
        val period = if (settings.generatorEnabled) {
            val n = if (settings.mode == "Spectrum") total else (settings.rtaWidth * rate).roundToInt()
            if (settings.mode == "RTA") Generators.pink(n, rate, settings.low, settings.high, 0.9)
            else Generators.chirp(n, rate, settings.low, settings.high, 0.9).also { data ->
                val fade = min(rate / 100, n / 2)
                for (i in 0 until fade) {
                    val gain = (0.5 - 0.5 * cos(PI * i / fade)).toFloat()
                    data[i] *= gain; data[n - 1 - i] *= gain
                }
            }
        } else null
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        check(minimum > 0) { "Recording format is unavailable" }
        val recorder = try { AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
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
                var offset = 0
                while (!s.stop.get()) {
                    if (offset == period.size) {
                        if (settings.mode == "Spectrum") {
                            // Keep the track alive while the final queued samples play.
                            while (!s.stop.get()) Thread.sleep(10)
                            break
                        }
                        offset = 0
                    }
                    val count = output.write(period, offset, min(1024, period.size - offset), AudioTrack.WRITE_BLOCKING)
                    if (count < 0) { if (!s.stop.get()) error("AudioTrack error: $count"); break }
                    offset += count
                }
            } catch (e: Exception) {
                if (!s.stop.get()) { fail(e.message ?: "Generator failed"); signalStop(s) }
            } finally {
                try { output.pause(); output.flush(); output.stop() } catch (_: Exception) { }
                output.release(); s.track = null; generating = false; changed()
            }
        }
        s.processor = thread(name = "BM-analysis") {
            try {
                val average = PowerAverage(settings.points())
                var lastPublish = 0L
                var lastPower: DoubleArray? = null
                var lastMs = 0.0
                fun publish() {
                    lastPower?.let { p -> plot(analyzer.plan.frequencies, DoubleArray(p.size) { 10*log10(p[it].coerceAtLeast(1e-20)) }, elapsed, frames, lastMs) }
                }
                val hop = if (settings.mode == "RTA") (settings.rtaHop * rate).roundToInt() else if (settings.onlineWelch) settings.welchHop else size
                val stream = FrameStream(size, hop) { frame ->
                    val startNs = System.nanoTime()
                    val p = analyzer.analyze(frame)
                    lastPower = if (settings.mode == "Spectrum" && settings.onlineWelch) average.add(p) else p
                    lastMs = (System.nanoTime() - startNs) / 1e6
                    frames++
                    val now = System.nanoTime()
                    if (now - lastPublish > 80_000_000L) { publish(); lastPublish = now }
                }
                val offline = if (settings.mode == "Spectrum" && !settings.onlineWelch) DoubleArray(total) else null
                var read = 0
                var lastStatus = 0L
                while (!s.done.get() || s.queue.isNotEmpty()) {
                    val item = s.queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    val (block, count) = item
                    if (offline != null) for (i in 0 until count) offline[read+i] = block[i].toDouble()
                    read += count
                    elapsed = read.toDouble()/rate
                    if (offline == null) stream.push(block, count)
                    s.pool.offer(block)
                    if (System.nanoTime() - lastStatus > 200_000_000L) { changed(); lastStatus = System.nanoTime() }
                }
                if (offline != null && read >= 2) {
                    val finalAnalyzer = if (read == size) analyzer else Analyzer(key.copy(size = read), cache)
                    val startNs = System.nanoTime()
                    val p = finalAnalyzer.analyze(if (read == size) offline else offline.copyOf(read))
                    frames = 1
                    plot(finalAnalyzer.plan.frequencies, DoubleArray(p.size) { 10*log10(p[it].coerceAtLeast(1e-20)) }, elapsed, frames, (System.nanoTime()-startNs)/1e6)
                } else publish()
                if (frames == 0 && read > 0 && settings.mode == "Spectrum") fail("Recording is shorter than a Welch window; no result")
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
        try { s.track?.pause(); s.track?.flush() } catch (_: Exception) { }
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
