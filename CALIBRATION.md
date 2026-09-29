# Built-in microphone calibration

Use this procedure to make the phone's built-in microphone more useful for approximate frequency-response measurements. It compares the built-in input with an external reference microphone. It does not establish calibrated sound-pressure level (SPL), and it does not make the built-in microphone a measurement-grade microphone.

## Prepare the setup

1. Connect the external measurement microphone to the phone and pair a broadband Bluetooth speaker. Set the speaker as Android's default media output. Under **Settings → Input**, select the external microphone and then open **Microphone calibration**. The app remembers that external input when calibration switches measurement back to the built-in microphone. With only one connected external input, it can select that input automatically.
2. Find the built-in microphone opening. Put the reference microphone capsule as close to that opening as practical, pointing in the same direction. Keep both microphones connected and in place for all four recordings.
3. Place the speaker at a fixed distance, facing both microphones. Keep the speaker, phone, microphones, media volume, Bluetooth connection, and Android media route unchanged throughout calibration. A USB interface may become the default output when connected, so verify that Android is sending media to the intended speaker. Disable speaker EQ, spatial audio, and adaptive sound modes where possible.
4. Use a quiet, stable space with minimal reverberation.
5. Choose a moderate speaker level. If the app reports clipping, check which input reached full scale. For the ECM8000-U, choose a lower **Input gain** under **Settings → Input**; the value is applied when you release the slider. Other microphones may require their own hardware control. Low-SNR errors name the affected microphone. Reduce background noise or raise the signal level without clipping before repeating calibration.

On the tested V2529, the ECM8000-U exposes USB input gain from −14 to +33 dB. The app changes this gain between recordings with a USB control request and restores the USB audio interfaces before capture. The microphone resets its gain after a physical reconnection, so read it again then. If recording fails after a gain change, reconnect the microphone.

## Record

Tap **Measure calibration** once. The app performs four recordings in this order; do not move the setup between them:

| Step | Input | Playback | Purpose |
| --- | --- | --- | --- |
| 1 | External reference | Off | Measure reference background noise (3 s). |
| 2 | Built-in microphone | Off | Measure built-in background noise (3 s). |
| 3 | External reference | On | Record the reference response to the test signal. |
| 4 | Built-in microphone | On | Record the built-in response to the same test signal. |

Each signal pass captures about 12 seconds: an initial quiet interval, a 0.2-second fade-in, three unchanged repetitions of one identical 3-second pink-noise waveform, a 0.2-second fade-out, and a trailing quiet interval. Background noise uses all six half-second windows from each 3-second capture. The app aligns each recording to the known waveform before comparing spectra. Bluetooth latency therefore need not be set manually.

The app calculates a correction only where **both** inputs have sufficient signal-to-noise ratio and the repeated periods within each recording agree well enough. It removes the overall microphone-level difference at 1 kHz (or the nearest valid edge). Correction is limited to ±20 dB. New calibration curves and diagnostic spectra use 0.5-octave smoothing; the same stored correction is applied during measurement. Repeat calibration to replace an older curve with this smoothing. In landscape, controls and instructions appear on the left and the graph on the right. The diagnostic graph shows both background-noise spectra (dashed), both signal spectra (solid), the correction, and the shaded valid range. The signals are aligned at 1 kHz or the nearest valid edge; each noise spectrum uses its signal's offset, preserving SNR. Displayed noise and signal spectra include de-pink (+10 dB/decade); the correction ratio is unchanged. Spectra use the left relative-dB axis; correction uses the right dB axis. Errors identify clipping or low SNR by microphone.

## Use the curve

Enable **Use calibration** and tap **Done**. Only the latest successful curve and its diagnostic spectra are stored; a failed attempt retains that curve. The switch retains your choice when inputs change. For the matching built-in input, the app limits the analysis band to the curve's valid range and applies correction before spectrum smoothing. External inputs bypass calibration without changing the switch. A new calibration also preserves the switch position. It rejects a calibration if the input device or recording configuration no longer matches.

If the room, microphone positions, speaker route, or sound processing changed during the four steps, repeat calibration. A physical calibration on V2529 with ECM8000-U and a Bluetooth speaker produced a valid range of approximately 59 Hz–17 kHz. Repeatability across setups and devices still needs testing.
