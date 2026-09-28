package com.bm.spectrum

import android.media.*
import android.os.Build
import android.util.Log
import kotlin.math.max

internal object MeasurementInput {
    const val AUTO = -1
    // Android also reports telephony, FM, remote-submix and echo-reference ports
    // as inputs. Only offer physical microphone/line routes to the user.
    fun selectableType(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> true
        else -> false
    }
    fun devices(manager: AudioManager): Array<AudioDeviceInfo> {
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { selectableType(it.type) }
        // Some phones expose front/bottom and rear ports with the same BUILTIN_MIC
        // type. Calibrate one stable primary route, preferring the non-rear port.
        val builtIn = inputs.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val primary = if (Build.VERSION.SDK_INT >= 28)
            builtIn.firstOrNull { !it.address.contains("back", ignoreCase = true) } ?: builtIn.firstOrNull()
        else builtIn.firstOrNull()
        return inputs.filter { it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC || it.id == primary?.id }.toTypedArray()
    }
    fun resolve(manager: AudioManager, id: Int): AudioDeviceInfo? {
        if (id != AUTO) return devices(manager).firstOrNull { it.id == id }
        return devices(manager).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                (Build.VERSION.SDK_INT >= 26 && it.type == AudioDeviceInfo.TYPE_USB_HEADSET)
        }
    }
    fun source(device: AudioDeviceInfo?): Int = if (device != null &&
        (device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET))
        MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.UNPROCESSED

    fun open(manager: AudioManager, rate: Int, deviceId: Int = AUTO): AudioRecord {
        val device = resolve(manager, deviceId)
        check(deviceId == AUTO || device != null) { "Selected input device is disconnected" }
        // On V2529, UNPROCESSED applies a steep high-pass even to USB line input.
        // VOICE_RECOGNITION measures flat through the same USB path. Keep the
        // previously chosen UNPROCESSED source for the phone's own microphone.
        val source = source(device)
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        check(minimum > 0) { "Recording format is unavailable" }
        val recorder = try {
            AudioRecord.Builder().setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(max(minimum * 4, rate * 4)).build()
        } catch (e: SecurityException) {
            throw IllegalStateException("Microphone access was revoked. Enable it in Android settings.", e)
        }
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable" }
            // Keep the USB-specific source paired with the device it was chosen for.
            if (device != null) check(recorder.setPreferredDevice(device)) { "Selected audio input is unavailable" }
            Log.i("BM-Spectrum", "Capture source=$source input=${device?.productName ?: "system default"}")
            return recorder
        } catch (e: Exception) {
            recorder.release()
            throw e
        }
    }
}
