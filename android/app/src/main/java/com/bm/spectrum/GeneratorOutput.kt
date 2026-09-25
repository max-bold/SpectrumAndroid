package com.bm.spectrum

import android.media.*
import android.os.Build
import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt

/** A mono generator stream, with a compatible hardware mixer for USB duplex. */
internal class GeneratorOutput private constructor(
    val track: AudioTrack,
    private val usbPcm16: Boolean,
    private val clearMixer: () -> Unit
) {
    private val pcm16 = if (usbPcm16) ShortArray(2048) else null

    // Counts here and playbackHeadPosition are frames, never interleaved samples.
    fun write(block: FloatArray, count: Int) {
        val shorts = pcm16
        if (shorts != null) monoToStereoPcm16(block, count, shorts)
        val samples = count * if (shorts != null) 2 else 1
        var offset = 0
        while (offset < samples) {
            val n = if (shorts != null) track.write(shorts, offset, samples - offset, AudioTrack.WRITE_BLOCKING)
                else track.write(block, offset, samples - offset, AudioTrack.WRITE_BLOCKING)
            check(n > 0) { "AudioTrack error: $n" }
            offset += n
        }
    }

    fun release() {
        try { track.release() } finally { clearMixer() }
    }

    companion object {
        fun open(manager: AudioManager, rate: Int): GeneratorOutput {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            var format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            var clearMixer: () -> Unit = {}
            var usbPcm16 = false
            var track: AudioTrack? = null
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val usb = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                    if (usb != null) {
                        // Changing AudioTrack's format alone leaves the vendor HAL mixer unchanged.
                        // Vivo/Creative X-Fi duplex stalls with the default mixer; PCM16 stereo
                        // at 48 kHz works. DEFAULT retains Android media volume and normal mixing.
                        val mixer = manager.getSupportedMixerAttributes(usb).firstOrNull {
                            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT &&
                                it.format.sampleRate == rate &&
                                it.format.encoding == AudioFormat.ENCODING_PCM_16BIT &&
                                it.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO
                        }
                        if (mixer != null && manager.setPreferredMixerAttributes(attributes, usb, mixer)) {
                            clearMixer = { manager.clearPreferredMixerAttributes(attributes, usb); Unit }
                            format = mixer.format
                            usbPcm16 = true
                            Log.i("BM-Spectrum", "USB generator mixer: PCM16 stereo $rate Hz (${usb.productName})")
                        }
                    }
                }
                val buffer = AudioTrack.getMinBufferSize(rate, format.channelMask, format.encoding)
                check(buffer > 0) { "Playback format is unavailable" }
                track = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                    .setBufferSizeInBytes(max(buffer * 2, 8192)).setTransferMode(AudioTrack.MODE_STREAM).build()
                check(track.state == AudioTrack.STATE_INITIALIZED) { "Audio output is unavailable" }
                return GeneratorOutput(track, usbPcm16, clearMixer)
            } catch (e: Exception) {
                try { track?.release() } finally { clearMixer() }
                throw e
            }
        }
    }
}

internal fun monoToStereoPcm16(input: FloatArray, count: Int, output: ShortArray) {
    require(count in 0..input.size && count <= output.size / 2)
    for (i in 0 until count) {
        val sample = (input[i].coerceIn(-1f, 1f) * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
        output[2 * i] = sample
        output[2 * i + 1] = sample
    }
}
