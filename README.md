# BM Spectrum for Android

A compact, offline audio analyzer based on desktop BM Spectrum. The main screen is one large, interactive graph with three controls: measurement, generator and settings. Signal processing runs in Kotlin on the phone; the interface uses React. No Python installation or server is needed.

## Measurement modes

- **RTA** displays a continuously updated bar spectrum with 1/3-, 1/6- or 1/12-octave frequency bands. Choose the analysis window and update interval.
- **Spectrum** records a measurement of a chosen duration and displays a smooth spectrum curve. It supports full-recording analysis and online Welch averaging.

Both modes use BM Spectrum's log-Gaussian smoothing and fixed de-pink correction. Levels are relative dB, not calibrated sound pressure levels (SPL).

### Spectrum

A selected measurement band, a spectrum curve and a cursor showing frequency and level:

![Spectrum mode with a frequency and level cursor](screenshots/Screenshot_20260921_114215.jpg)

### RTA

Octave-band bars with the same interactive cursor:

![RTA mode with octave-band bars and a cursor](screenshots/Screenshot_20260921_114252.jpg)

## Using the app

1. Open **Settings**, choose **RTA** or **Spectrum**, set the frequency band and measurement parameters, then tap **Done**.
2. Enable the **Generator** button if you want the phone to produce the measurement signal. This button arms playback; it does not start sound by itself.
3. Tap **Play** and allow microphone access when requested. RTA uses IFFT pink noise; Spectrum uses a logarithmic chirp. The generator starts with the measurement.
4. Tap **Stop** to finish. Spectrum also stops automatically after its configured duration. Playback stops with measurement; the result remains on the graph.

Generator peak amplitude is fixed at **0.9**, approximately **−1 dBFS**. Use Android media volume to adjust playback loudness. With the generator disabled, the app measures incoming sound only.

Opening settings or putting the app in the background stops measurement and playback. The screen stays awake while measuring. Settings are saved on the device.

## Graph controls

- **Tap or drag horizontally:** select a frequency and read its level.
- **Drag vertically:** move the dB scale.
- **Pinch:** zoom the dB scale.
- **Double-tap:** reset the dB scale and clear the cursor.

The logarithmic frequency axis stays fixed at **20 Hz–20 kHz**. A narrower measurement band is highlighted. During Spectrum measurement, a small progress bar follows the approximate sweep position along the bottom of the graph.

The logo and controls sit over the graph. In landscape orientation the buttons form a vertical column at the upper right. Settings use compact sliders and a two-column landscape layout.

## Defaults

| Setting | Default |
| --- | --- |
| Mode | RTA |
| Frequency band | 20 Hz–20 kHz |
| RTA window / hop | 3 s / 0.1 s |
| RTA bands | 1/3 octave |
| Spectrum duration | 5 s |
| Spectrum smoothing | 0.3 octave |
| Spectrum points | 256 |
| Online Welch | Enabled |
| Welch window / hop | 8192 / 4096 samples |
| Generator | Disabled |

Slider ranges are provisional; their final limits are a planned v0.2 discussion.

## Current scope

Capture and playback use standard Android audio at mono 48 kHz; Android handles routing and resampling. This version has no reference channel, calibrated SPL, file import/export or saved audio recordings. Spectrum capture is analyzed in memory. The octave frequency grid is standard, but analysis uses Gaussian smoothing rather than an IEC-certified filter bank.

Numerical DSP tests and device checks have passed on a V2529 running Android 16. External-input spectrum slope and physical generator-output verification remain on the [roadmap](TODO.md).

## Build and install

The minimum Android API level is 24 (Android 7.0). Building requires Node.js 22.12+ (or a compatible newer release), JDK 21 and Android SDK platform 36 with build tools. Run from the project root:

```powershell
npm ci
npm run sync
./scripts/android.ps1
& C:/platform-tools/adb.exe install -r android/app/build/outputs/apk/debug/app-debug.apk
& C:/platform-tools/adb.exe shell am start -n com.bm.spectrum/.MainActivity
```

The PowerShell helper detects a local JDK under `.tools/java/` and SDK under `.tools/android-sdk/` if present. These tools are not included in version control. Otherwise configure `JAVA_HOME` and `android/local.properties` for your installation. Adjust the `adb` path as needed.

The debug APK is generated at `android/app/build/outputs/apk/debug/app-debug.apk`. USB installation requires an authorized debugging connection.

`npm run dev` previews the interface in a browser; recording and playback require the Android app.

## Development

See [AGENTS.md](AGENTS.md) for the project structure, DSP decisions, build commands and verification workflow. See [TODO.md](TODO.md) for completed v0.1 work and v0.2 plans.

Built with [React](https://react.dev/), [Capacitor](https://capacitorjs.com/) and [JTransforms](https://github.com/wendykierp/JTransforms).
