package com.bm.spectrum

import android.Manifest
import com.getcapacitor.*
import com.getcapacitor.annotation.*
import com.bm.spectrum.dsp.Settings
import org.json.JSONArray
import java.util.concurrent.Executors

@CapacitorPlugin(name = "Spectrum", permissions = [Permission(alias = "microphone", strings = [Manifest.permission.RECORD_AUDIO])])
class SpectrumPlugin : Plugin() {
    private val control = Executors.newSingleThreadExecutor()
    private lateinit var engine: AudioEngine
    override fun load() {
        engine = AudioEngine({
            activity.runOnUiThread {
                if (engine.running) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            notifyListeners("state", state())
        }) { f, db, elapsed, frames, ms ->
            notifyListeners("plot", JSObject().put("frequency", JSONArray(f.toList())).put("db", JSONArray(db.toList()))
                .put("elapsed", elapsed).put("frames", frames).put("analysisMs", ms))
        }
    }
    private fun state(): JSObject = JSObject().put("running", engine.running).put("generating", engine.generating)
        .put("error", engine.error ?: "").put("elapsed", engine.elapsed).put("frames", engine.frames).put("cacheBuilds", engine.cache.builds)

    private fun settings(call: PluginCall): Settings {
        val o = call.getObject("settings") ?: JSObject()
        return Settings(
            mode=o.optString("mode", "RTA"), low=o.optDouble("low", 20.0), high=o.optDouble("high", 20000.0),
            duration=o.optDouble("duration", 5.0), smoothing=o.optDouble("smoothing", 0.3), spectrumPoints=o.optInt("spectrumPoints", 256),
            onlineWelch=o.optBoolean("onlineWelch", true), welchSize=o.optInt("welchSize", 8192), welchHop=o.optInt("welchHop", 4096),
            rtaWidth=o.optDouble("rtaWidth", 3.0), rtaHop=o.optDouble("rtaHop", 0.1), rtaFraction=o.optInt("rtaFraction", 3),
            generatorEnabled=o.optBoolean("generatorEnabled", false)
        ).also { it.validate() }
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
        else execute(call) { engine.start(settings(call)) }
    }
    @PermissionCallback private fun microphoneResult(call: PluginCall) {
        if (getPermissionState("microphone") == PermissionState.GRANTED) start(call)
        else call.reject("Microphone access is required. Enable it in Android settings.")
    }
    @PluginMethod fun stop(call: PluginCall) = execute(call) { engine.stopAll() }
    override fun handleOnPause() { control.execute { engine.stopAll() } }
    override fun handleOnDestroy() { control.execute { engine.stopAll() }; control.shutdown() }
}
