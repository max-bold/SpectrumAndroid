package com.bm.spectrum

import android.media.*
import android.util.Log
import com.bm.spectrum.dsp.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.*

class AudioEngine(private val audioManager: AudioManager, private val changed: () -> Unit,
                  private val plot: (MeasurementPlot) -> Unit, private val level: (Double) -> Unit) {
    val rate = 48000
    val cache = PlanCache()
    @Volatile var running = false; private set
    @Volatile var generating = false; private set
    @Volatile var error: String? = null; private set
    @Volatile var elapsed = 0.0; private set
    @Volatile var sweepLead = 0.0; private set
    @Volatile var frames = 0; private set
    @Volatile private var session: Session? = null
    @Volatile private var calibrationCancelled = false
    @Volatile private var calibrationRecord: AudioRecord? = null
    @Volatile var calibrationPhase = ""; private set

    private class Session {
        val stop = AtomicBoolean(false)
        val done = AtomicBoolean(false)
        val queue = ArrayBlockingQueue<Pair<FloatArray, Int>>(128)
        val pool = ArrayBlockingQueue<FloatArray>(130).apply { repeat(130) { add(FloatArray(1024)) } }
        var latest: LatestWindow? = null
        @Volatile var record: AudioRecord? = null
        var reader: Thread? = null
        var processor: Thread? = null
        var writer: Thread? = null
        @Volatile var output: GeneratorOutput? = null
    }

    @Synchronized fun start(settings: Settings, requestedCalibration: CalibrationCurve? = null) {
        settings.validate(rate)
        check(!running) { "A measurement is already running" }
        stopMeasurement()
        error = null; elapsed = 0.0; frames = 0
        sweepLead = if (settings.mode == "Spectrum" && settings.generatorEnabled) GENERATOR_FADE_SECONDS else 0.0
        // Prepare both Welch and full-recording plans before opening the microphone.
        val selected = MeasurementInput.resolve(audioManager, settings.inputDeviceId)
        val calibration = requestedCalibration.takeIf { selected?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        check(settings.inputDeviceId == MeasurementInput.AUTO || selected != null) { "Selected input device is disconnected" }
        if (calibration != null) {
            calibration.validate()
            check(selected != null && calibration.matches(selected.id, rate, MeasurementInput.source(selected))) { "Calibration does not match the selected input" }
        }
        val band = calibration?.restricted(settings.low, settings.high)
        val effective = if (band == null) settings else settings.copy(low=band.first, high=band.second)
        val measurement = Measurement(effective, rate, cache, calibration, plot)
        val total = measurement.total
        val s = Session()
        if (settings.mode == "RTA") s.latest = LatestWindow((settings.rtaWidth * rate).roundToInt(), (settings.rtaHop * rate).roundToInt())
        val period = if (!settings.generatorEnabled) null else if (settings.mode == "RTA")
            Generators.pink((settings.rtaWidth * rate).roundToInt(), rate, effective.low, effective.high, 0.9)
        else Sweep(settings.duration, rate, effective.low, effective.high).signal()
        val recorder = MeasurementInput.open(audioManager, rate, settings.inputDeviceId)
        try {
            if (period != null) s.output = GeneratorOutput.open(audioManager, rate)
            recorder.startRecording()
            if (settings.inputDeviceId != MeasurementInput.AUTO) check(recorder.routedDevice?.id == selected?.id) { "Selected input was not routed" }
            s.output?.track?.play()
        } catch (e: Exception) { recorder.release(); s.output?.release(); throw e }
        s.record = recorder; session = s; running = true; generating = period != null; changed()
        if (period != null) s.writer = thread(name = "BM-generator") {
            val output = s.output!!
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
                    output.write(block, count)
                    written += count
                    position += count
                }
                // Drain the queued fade instead of flushing it away on stop.
                val deadline = System.nanoTime() + 2_000_000_000L
                while ((output.track.playbackHeadPosition.toLong() and 0xffffffffL) < written && System.nanoTime() < deadline) Thread.sleep(5)
            } catch (e: Exception) {
                if (!s.stop.get()) { fail(e.message ?: "Generator failed"); signalStop(s) }
            } finally {
                try { output.track.pause(); output.track.flush(); output.track.stop() } catch (_: Exception) { }
                output.release(); s.output = null; generating = false; changed()
            }
        }
        s.processor = thread(name = "BM-analysis") {
            try {
                var lastStatus = 0L
                if (settings.mode == "RTA") {
                    var lastProcessed = 0L
                    while (!s.done.get()) {
                        val snapshot = s.latest!!.newestAfter(lastProcessed)
                        if (snapshot == null) { Thread.sleep(10); continue }
                        measurement.pushLatest(snapshot)
                        lastProcessed = snapshot.captured
                        elapsed = measurement.elapsed; frames = measurement.frames
                        if (System.nanoTime() - lastStatus > 200_000_000L) { changed(); lastStatus = System.nanoTime() }
                    }
                } else while (!s.done.get() || s.queue.isNotEmpty()) {
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
            var levelPeak = 0.0
            var lastLevelAt = System.nanoTime()
            val rtaBlock = if (settings.mode == "RTA") FloatArray(1024) else null
            try {
                while (!s.stop.get() && (settings.mode == "RTA" || captured < total)) {
                    val block = rtaBlock ?: (s.pool.poll() ?: error("Analysis cannot keep up with recording"))
                    val wanted = if (settings.mode == "Spectrum") min(block.size, total-captured) else block.size
                    val count = recorder.read(block, 0, wanted, AudioRecord.READ_BLOCKING)
                    if (count < 0) { if (rtaBlock == null) s.pool.offer(block); if (!s.stop.get()) error("AudioRecord error: $count"); break }
                    if (count == 0) { if (rtaBlock == null) s.pool.offer(block); continue }
                    if (calibration != null && recorder.routedDevice?.id != selected?.id) error("Calibrated input route changed")
                    for (i in 0 until count) levelPeak = max(levelPeak, abs(block[i].toDouble()))
                    val now = System.nanoTime()
                    if (now - lastLevelAt >= 80_000_000L) {
                        level(20.0 * log10(levelPeak.coerceAtLeast(1e-4)).coerceIn(-80.0, 0.0))
                        levelPeak = 0.0; lastLevelAt = now
                    }
                    captured += count
                    if (rtaBlock != null) s.latest!!.add(block, count)
                    else if (!s.queue.offer(block to count)) { s.pool.offer(block); error("Analysis cannot keep up with recording; increase hop") }
                }
            } catch (e: Exception) { if (!s.stop.get()) fail(e.message ?: "Recording failed") }
            finally {
                level(-80.0)
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
    fun cancelCalibration() {
        calibrationCancelled = true
        try { calibrationRecord?.stop() } catch (_: Exception) { }
    }
    fun calibrate(referenceId: Int, phoneId: Int, low: Double, high: Double): CalibrationCurve {
        check(!running) { "A measurement is already running" }
        val reference = if(referenceId<0) null else MeasurementInput.resolve(audioManager, referenceId)
        val phone = MeasurementInput.resolve(audioManager, phoneId)
        check(reference != null && reference.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) { "External microphone is not connected. Connect it and select it under Input in Settings." }
        check(phone != null && reference.id != phone.id) { "Built-in microphone is unavailable. Select separate built-in and external inputs." }
        check(phone.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && reference.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) { "Select built-in and external microphones" }
        check(low >= 20 && high <= 20000 && high > low) { "Invalid calibration band" }
        calibrationCancelled = false; error = null; running = true; elapsed = 0.0; frames = 0; calibrationPhase = "reference-noise"; changed()
        try {
            val phaseRandom = java.util.Random(0x424D5350L)
            val period = Generators.pink(3 * rate, rate, low, high, 0.9, DoubleArray(3*rate/2+1) {
                phaseRandom.nextDouble()*2*PI
            })
            val refAmbient = captureCalibrationNoise(referenceId, 0.0)
            calibrationPhase = "phone-noise"; changed()
            val phoneAmbient = captureCalibrationNoise(phoneId, CalibrationLimits.AMBIENT_SECONDS.toDouble())
            calibrationPhase = "reference-signal"; changed()
            val refPcm = captureCalibrationPass(referenceId, period, 2.0 * CalibrationLimits.AMBIENT_SECONDS)
            calibrationPhase = "phone-signal"; changed()
            val phonePcm = captureCalibrationPass(phoneId, period, 2.0 * CalibrationLimits.AMBIENT_SECONDS + 12.0)
            check(!calibrationCancelled) { "Calibration cancelled" }
            calibrationPhase = "analyzing"; changed()
            return CalibrationAnalysis.measure(phonePcm,refPcm,phoneAmbient,refAmbient,period,rate,phone.id,MeasurementInput.source(phone))
        } finally {
            calibrationRecord = null; running = false; generating = false; calibrationPhase = ""; changed()
        }
    }
    private fun captureCalibrationNoise(deviceId: Int, offsetSeconds: Double): FloatArray {
        check(!calibrationCancelled) { "Calibration cancelled" }
        val samples = FloatArray(rate * CalibrationLimits.AMBIENT_SECONDS)
        val recorder = MeasurementInput.open(audioManager, rate, deviceId)
        calibrationRecord = recorder
        try {
            recorder.startRecording()
            check(recorder.routedDevice?.id == deviceId) { "Selected calibration input was not routed" }
            var captured = 0
            val deadline = System.nanoTime() + (CalibrationLimits.AMBIENT_SECONDS + 3L) * 1_000_000_000L
            var lastStatus = 0L
            while (captured < samples.size && !calibrationCancelled) {
                check(System.nanoTime() < deadline) { "Background-noise recording timed out" }
                val count = recorder.read(samples, captured, samples.size - captured, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "AudioRecord error: $count" }
                if (count == 0) { Thread.sleep(5); continue }
                captured += count
                elapsed = offsetSeconds + captured.toDouble() / rate
                if (System.nanoTime() - lastStatus > 200_000_000L) { changed(); lastStatus = System.nanoTime() }
            }
            check(!calibrationCancelled) { "Calibration cancelled" }
            check(recorder.routedDevice?.id == deviceId) { "Calibration input route changed" }
            return samples
        } finally {
            try { recorder.stop() } catch (_: Exception) { }
            recorder.release(); calibrationRecord = null
        }
    }
    private fun captureCalibrationPass(deviceId: Int, period: FloatArray, offsetSeconds: Double): FloatArray {
        check(!calibrationCancelled) { "Calibration cancelled" }
        val playback = calibrationPlayback(period, rate)
        val capacity = 12 * rate
        val samples = FloatArray(capacity)
        val recorder = MeasurementInput.open(audioManager,rate,deviceId)
        val output = try { GeneratorOutput.open(audioManager,rate) } catch (e: Exception) {recorder.release();throw e}
        calibrationRecord = recorder
        try {
            recorder.startRecording()
            check(recorder.routedDevice?.id == deviceId) { "Selected calibration input was not routed" }
        } catch (e: Exception) { recorder.release(); calibrationRecord=null; output.release(); throw e }
        var captured = 0
        var readError: Throwable? = null
        var lastStatus = 0L
        val reader = thread(name="BM-calibration-record") {
            val block=FloatArray(1024)
            try {
                while(captured<capacity && !calibrationCancelled) {
                    val count=recorder.read(block,0,min(block.size,capacity-captured),AudioRecord.READ_BLOCKING)
                    check(count>=0) { "AudioRecord error: $count" }
                    if(count>0) {
                        block.copyInto(samples,captured,0,count);captured+=count
                        elapsed=offsetSeconds+captured.toDouble()/rate
                        if(System.nanoTime()-lastStatus>200_000_000L) {changed();lastStatus=System.nanoTime()}
                    }
                }
            } catch(e: Throwable) {if(!calibrationCancelled)readError=e}
        }
        try {
            Thread.sleep(500)
            check(!calibrationCancelled) { "Calibration cancelled" }
            output.track.play(); generating=true;changed()
            val block=FloatArray(1024)
            for (position in 0 until playback.size step block.size) {
                check(!calibrationCancelled) { "Calibration cancelled" }
                val count=min(block.size,playback.size-position)
                for(i in 0 until count)block[i]=playback[position+i]
                output.write(block,count)
            }
            val target=(playback.size).toLong()
            val deadline=System.nanoTime()+3_000_000_000L
            while((output.track.playbackHeadPosition.toLong() and 0xffffffffL)<target && System.nanoTime()<deadline && !calibrationCancelled)Thread.sleep(10)
            generating=false;changed()
            reader.join(5000)
            if(reader.isAlive) {calibrationCancelled=true;try {recorder.stop()} catch (_:Exception) {};error("Calibration recording timed out")}
            readError?.let {throw IllegalStateException("Calibration recording failed",it)}
            check(!calibrationCancelled) { "Calibration cancelled" }
            return samples.copyOf(captured)
        } finally {
            calibrationCancelled = calibrationCancelled || readError != null
            try {recorder.stop()} catch (_:Exception) {}
            reader.join(2000)
            recorder.release();calibrationRecord=null
            try {output.track.pause();output.track.flush();output.track.stop()} catch (_:Exception) {}
            output.release();generating=false;changed()
        }
    }
    fun stopAll() { cancelCalibration(); stopMeasurement() }
}
