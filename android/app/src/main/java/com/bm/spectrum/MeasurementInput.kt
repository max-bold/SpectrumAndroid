package com.bm.spectrum

import android.media.*
import android.os.Build
import android.util.Log
import kotlin.math.max

internal object MeasurementInput {
    fun open(manager: AudioManager, rate: Int): AudioRecord {
        val usb = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                (Build.VERSION.SDK_INT >= 26 && it.type == AudioDeviceInfo.TYPE_USB_HEADSET)
        }
        // On V2529, UNPROCESSED applies a steep high-pass even to USB line input.
        // VOICE_RECOGNITION measures flat through the same USB path. Keep the
        // previously chosen UNPROCESSED source for the phone's own microphone.
        val source = if (usb != null) MediaRecorder.AudioSource.VOICE_RECOGNITION
            else MediaRecorder.AudioSource.UNPROCESSED
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
            if (usb != null) check(recorder.setPreferredDevice(usb)) { "USB audio input is unavailable" }
            Log.i("BM-Spectrum", "Capture source=$source input=${usb?.productName ?: "system default"}")
            return recorder
        } catch (e: Exception) {
            recorder.release()
            throw e
        }
    }
}
