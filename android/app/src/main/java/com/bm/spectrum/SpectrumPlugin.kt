package com.bm.spectrum

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import com.getcapacitor.*
import com.getcapacitor.annotation.*
import com.bm.spectrum.dsp.Settings
import com.bm.spectrum.dsp.CalibrationCurve
import org.json.JSONArray
import android.media.AudioManager
import android.media.AudioDeviceInfo
import java.util.concurrent.Executors

@CapacitorPlugin(name = "Spectrum", permissions = [Permission(alias = "microphone", strings = [Manifest.permission.RECORD_AUDIO])])
class SpectrumPlugin : Plugin() {
    private val control = Executors.newSingleThreadExecutor()
    private lateinit var engine: AudioEngine
    private var usbProbeReceiver: BroadcastReceiver? = null
    override fun load() {
        engine = AudioEngine(context.getSystemService(android.media.AudioManager::class.java), {
            activity.runOnUiThread {
                if (engine.running) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            notifyListeners("state", state())
        }, { p ->
            notifyListeners("plot", JSObject().put("frequency", JSONArray(p.frequency.toList())).put("db", JSONArray(p.db.toList()))
                .put("elapsed", p.elapsed).put("frames", p.frames).put("analysisMs", p.ms).put("kind", p.kind).put("fftSize", p.fftSize))
        }, { dbfs -> notifyListeners("level", JSObject().put("dbfs", dbfs)) })
    }
    private fun state(): JSObject = JSObject().put("running", engine.running).put("generating", engine.generating)
        .put("error", engine.error ?: "").put("elapsed", engine.elapsed).put("sweepLead", engine.sweepLead).put("frames", engine.frames)
        .put("cacheBuilds", engine.cache.builds).put("calibrationPhase", engine.calibrationPhase)

    private fun settings(call: PluginCall): Settings {
        val o = call.getObject("settings") ?: JSObject()
        return Settings(
            mode=o.optString("mode", "RTA"), low=o.optDouble("low", 20.0), high=o.optDouble("high", 20000.0),
            duration=o.optDouble("duration", 5.0), smoothing=o.optDouble("smoothing", 0.3), spectrumPoints=o.optInt("spectrumPoints", 256),
            onlineWelch=o.optBoolean("onlineWelch", true), welchSize=o.optInt("welchSize", 8192), welchHop=o.optInt("welchHop", 4096),
            rtaWidth=o.optDouble("rtaWidth", 3.0), rtaHop=o.optDouble("rtaHop", 0.1), rtaFraction=o.optInt("rtaFraction", 3),
            generatorEnabled=o.optBoolean("generatorEnabled", false), inputDeviceId=o.optInt("inputDeviceId", -1)
        ).also { it.validate() }
    }
    private fun calibration(call: PluginCall): CalibrationCurve? {
        val o=call.getObject("calibration") ?: return null
        fun numbers(name: String): DoubleArray {val a=o.getJSONArray(name);return DoubleArray(a.length()){a.getDouble(it)}}
        return CalibrationCurve(o.getInt("inputDeviceId"),o.getInt("sampleRate"),o.getInt("audioSource"),
            numbers("frequencies"),numbers("correctionDb"),o.getDouble("fMin"),o.getDouble("fMax"),
            if(o.isNull("sensitivityDbSpl"))null else o.optDouble("sensitivityDbSpl"))
    }
    private fun execute(call: PluginCall, action: () -> Unit) {
        control.execute { try { action(); call.resolve(state()) } catch (e: Exception) { engine.fail(e.message ?: "Error"); call.reject(e.message, e) } }
    }
    @PluginMethod fun getState(call: PluginCall) {
        activity.runOnUiThread {
            call.resolve(state().put("keepScreenOn", activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0))
        }
    }
    @PluginMethod fun start(call: PluginCall) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) requestPermissionForAlias("microphone", call, "microphoneResult")
        else execute(call) { engine.start(settings(call),calibration(call)) }
    }
    @PermissionCallback private fun microphoneResult(call: PluginCall) {
        if (getPermissionState("microphone") == PermissionState.GRANTED) start(call)
        else call.reject("Microphone access is required. Enable it in Android settings.")
    }
    @PluginMethod fun listInputs(call: PluginCall) {
        val manager=context.getSystemService(AudioManager::class.java)
        val result=JSONArray()
        val usb=context.getSystemService(UsbManager::class.java)
        val ecm8000=usb.deviceList.values.any { it.vendorId==12390 && it.productId==39321 }
        for(device in MeasurementInput.devices(manager)) result.put(JSObject().put("id",device.id)
            .put("name",if(device.type==AudioDeviceInfo.TYPE_BUILTIN_MIC) "Built-in microphone" else device.productName.toString()).put("type",device.type)
            .put("builtIn",device.type==AudioDeviceInfo.TYPE_BUILTIN_MIC)
            .put("gainControl",ecm8000 && device.productName.toString()=="ECM8000-U"))
        val defaultInput=MeasurementInput.resolve(manager,MeasurementInput.AUTO)
            ?: MeasurementInput.devices(manager).firstOrNull { it.type==AudioDeviceInfo.TYPE_BUILTIN_MIC }
        call.resolve(JSObject().put("devices",result).put("defaultInputId",defaultInput?.id ?: -1))
    }
    @PluginMethod fun inputGain(call: PluginCall) {
        if (engine.running || engine.calibrationPhase.isNotEmpty()) { call.reject("Stop recording before adjusting input gain"); return }
        val selected = MeasurementInput.devices(context.getSystemService(AudioManager::class.java))
            .firstOrNull { it.id == call.getInt("deviceId") }
        if (selected?.productName?.toString() != "ECM8000-U") { call.reject("Gain control is unavailable for this input"); return }
        val manager = context.getSystemService(UsbManager::class.java)
        val device = manager.deviceList.values.firstOrNull { it.vendorId == 12390 && it.productId == 39321 }
        if (device == null) { call.reject("USB microphone is unavailable"); return }
        if (!manager.hasPermission(device)) {
            if (usbProbeReceiver != null) { call.reject("USB permission request is already open"); return }
            val action = "${context.packageName}.USB_GAIN_PROBE"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    context.unregisterReceiver(this)
                    usbProbeReceiver = null
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) inputGain(call)
                    else call.reject("USB microphone access was denied")
                }
            }
            usbProbeReceiver = receiver
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, IntentFilter(action))
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags))
            return
        }
        val connection = manager.openDevice(device)
        if (connection == null) { call.reject("Cannot open USB microphone"); return }
        val streaming = device.getInterface(1)
        val audioControl = device.getInterface(0)
        var streamingClaimed = false
        var controlClaimed = false
        try {
            streamingClaimed = connection.claimInterface(streaming, true)
            controlClaimed = connection.claimInterface(audioControl, true)
            check(streamingClaimed && controlClaimed) { "Cannot claim USB audio interfaces" }
            call.getInt("gainDb")?.let { requestedGain ->
                require(requestedGain in -14..33) { "Gain must be between −14 and +33 dB" }
                val raw = requestedGain * 256
                val value = byteArrayOf(raw.toByte(), (raw shr 8).toByte())
                check(connection.controlTransfer(0x21, 0x01, 0x0200, 0x0600, value, 2, 1000) == 2) { "USB gain write failed" }
            }
            val current = ByteArray(2)
            check(connection.controlTransfer(0xa1, 0x81, 0x0200, 0x0600, current, 2, 1000) == 2)
            val currentDb = (((current[0].toInt() and 0xff) or (current[1].toInt() shl 8)).toShort().toInt()) / 256.0
            val streamingReleased = connection.releaseInterface(streaming)
            streamingClaimed = false
            val controlReleased = connection.releaseInterface(audioControl)
            controlClaimed = false
            check(streamingReleased && controlReleased) { "USB audio could not be restored; reconnect the microphone" }
            call.resolve(JSObject().put("gainDb", currentDb).put("minDb", -14).put("maxDb", 33))
        } catch (e: Exception) { call.reject(e.message ?: "USB gain probe failed", e)
        } finally {
            if (streamingClaimed) connection.releaseInterface(streaming)
            if (controlClaimed) connection.releaseInterface(audioControl)
            connection.close()
        }
    }
    @PluginMethod fun calibrate(call: PluginCall) {
        if(getPermissionState("microphone")!=PermissionState.GRANTED) {requestPermissionForAlias("microphone",call,"calibrationPermission");return}
        control.execute {
            try {
                val curve=engine.calibrate(call.getInt("referenceId") ?: -1,call.getInt("phoneId") ?: -1,
                    call.getDouble("low") ?: 20.0,call.getDouble("high") ?: 20000.0)
                call.resolve(JSObject().put("inputDeviceId",curve.inputDeviceId).put("sampleRate",curve.sampleRate)
                    .put("audioSource",curve.audioSource).put("frequencies",JSONArray(curve.frequencies.toList()))
                    .put("correctionDb",JSONArray(curve.correctionDb.toList())).put("responseDb",JSONArray(curve.responseDb.toList()))
                    .put("fMin",curve.fMin).put("fMax",curve.fMax)
                    .put("spectra",curve.spectra?.let { s -> JSObject()
                        .put("frequencies",JSONArray(s.frequencies.toList()))
                        .put("phoneNoiseDb",JSONArray(s.phoneNoiseDb.toList()))
                        .put("referenceNoiseDb",JSONArray(s.referenceNoiseDb.toList()))
                        .put("phoneSignalDb",JSONArray(s.phoneSignalDb.toList()))
                        .put("referenceSignalDb",JSONArray(s.referenceSignalDb.toList())) })
                    .put("averageRepeatability",curve.averageRepeatability).put("phoneSnrDb",curve.phoneSnrDb).put("referenceSnrDb",curve.referenceSnrDb))
            } catch(e:Exception) {engine.fail(e.message ?: "Calibration failed");call.reject(e.message,e)}
        }
    }
    @PermissionCallback private fun calibrationPermission(call: PluginCall) {
        if(getPermissionState("microphone")==PermissionState.GRANTED)calibrate(call)
        else call.reject("Microphone access is required")
    }
    @PluginMethod fun stop(call: PluginCall) { engine.cancelCalibration(); execute(call) { engine.stopAll() } }
    override fun handleOnPause() { engine.cancelCalibration(); control.execute { engine.stopAll() } }
    override fun handleOnDestroy() {
        usbProbeReceiver?.let { context.unregisterReceiver(it) }
        usbProbeReceiver = null
        control.execute { engine.stopAll() }; control.shutdown()
    }
}
