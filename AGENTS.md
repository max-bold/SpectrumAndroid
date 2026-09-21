# Project guide for future contributors and agents

## Purpose and scope

BM Spectrum for Android is a simplified, offline companion to desktop BM Spectrum. Keep the interface minimal: one large graph and a settings screen. All user-visible labels, warnings and errors must be in English.

The implementation uses React 19, TypeScript and Vite for the UI, Capacitor 8 for the bridge, Kotlin for DSP/audio and JTransforms 3.1 for FFT. Do not introduce a Python runtime or Chaquopy. Python is only a development reference for numerical fixtures.

On the current development machine the desktop checkout is `D:/Code/Spectrum` and this project is `D:/Code/SpectrumAndroid`. These local paths are not portable dependencies of the app.

## Source map

Native paths below are relative to `android/app/src/main/java/com/bm/spectrum/`.

| Path | Responsibility |
| --- | --- |
| `src/App.tsx` | Main controls, settings, lifecycle listeners and bridge calls |
| `src/Chart.tsx` | Canvas graph, log grid, cursor, Y gestures, band shading and sweep progress |
| `src/model.ts` | TypeScript types, defaults, validation, storage migration and plugin interface |
| `src/settings-controls.ts` | Provisional slider ranges and dependent-value updates |
| `src/style.css` | Layout, safe areas, settings and orientation rules |
| `src/main.tsx` | React entry point |
| Native `MainActivity.java` | Capacitor activity and plugin registration |
| Native `SpectrumPlugin.kt` | Permissions, serialized control calls, events and screen-awake flag |
| Native `AudioEngine.kt` | Recording/playback sessions, worker threads, queueing and shutdown |
| Native `dsp/Dsp.kt` | Gaussian plans/cache, FFT analysis, streaming frames, power averaging and generators |
| Native `dsp/Measurement.kt` | Welch preview/full-recording final analysis, extended sweep and fade constant |
| Native `dsp/OctaveBands.kt` | Base-10 fractional-octave center frequencies |
| Native `dsp/Settings.kt` | Native defaults, validation, point counts and smoothing width |
| `android/app/src/test/java/com/bm/spectrum/dsp/DspTest.kt` | Numerical regression tests |
| `android/app/src/test/resources/reference.json` | Desktop/NumPy/SciPy reference vectors |
| `scripts/android.ps1` | Local JDK/SDK discovery and Gradle wrapper invocation |
| `scripts/reference_fixtures.py` | Regenerates reference vectors using desktop code |
| `scripts/webview.mjs` | ADB forwarding and WebView DevTools helpers |
| `scripts/device-smoke.mjs` | Real-device checks; restores saved settings afterward |
| `capacitor.config.ts` | App identity, web directory and system-bar configuration |
| `logo/` | Supplied branding; UI imports `White@4x.png` |
| `screenshots/` | User-facing screenshots referenced by README |
| `TODO.md` | Completed work and remaining v0.2 tasks |

`dist/`, Android build output, copied web assets and Capacitor-generated files are build products. Edit UI sources in `src/`, then sync; do not patch bundled JavaScript. `.tools/`, `node_modules/`, `test-results/`, APKs and local SDK configuration are ignored.

## Product decisions to preserve

- RTA continuously updates bars; Spectrum uses optional online Welch only as a preview and always finishes with a full-recording periodogram, including early stops.
- Generator choice follows mode: Spectrum uses one logarithmic chirp; RTA repeats IFFT pink noise. There is no separate generator selector or gain setting.
- The generator button arms playback for the next measurement. It does not independently start or stop sound. Peak amplitude is 0.9 (approximately −1 dBFS); Android media volume controls loudness.
- Measurement and playback share a session. Manual stop, automatic completion, opening settings and backgrounding stop audio. Keep the screen awake only while measuring; there is no background service.
- Use standard `AudioRecord` and `AudioTrack`, mono 48 kHz float PCM. Precise latency alignment, custom routing and resampling are outside current scope.
- No reference input or calibrated SPL. Display relative de-pink dB. File import/export and persistent audio recording are not implemented.
- Only log-Gaussian smoothing is supported. RTA uses 1/3-, 1/6- or 1/12-octave rows, not an arbitrary bar count.
- The graph occupies the main screen. PNG branding overlays the upper left; landscape controls form a vertical column over the upper right. Preserve Android safe areas on all four sides, including navigation bars and cutouts.
- X is fixed at 20–20000 Hz with a logarithmic grid. Y pans and zooms. Its vertical label includes the mode (`RTA dB` / `Spectrum dB`); `Hz` is centered below X.
- Tap/horizontal drag selects the nearest measured frequency. Vertical drag pans Y; pinch zooms; double-tap resets Y and clears the cursor.
- Restricted bands are shaded. Spectrum progress deliberately approximates the sweep by interpolating between log-frequency endpoints over the configured duration. Do not add precise playback synchronization for this indicator.
- No persistent status row, sample count or `de-pink` caption. Errors remain visible when relevant.
- Numeric settings use compact sliders. The user accepted the existing ranges for v0.2. Welch controls display seconds but retain exact sample counts and power-of-two window choices internally, preserving stored settings.

## DSP contract

1. Subtract each frame's mean. Use periodic Hann for Welch and RTA without the generator, boxcar for RTA with the generator, and a rectangular window for full-recording Spectrum.
2. Calculate one-sided PSD, normalized by sample rate and window energy. Double interior bins only; handle DC and Nyquist correctly.
3. Apply de-pink as `power * f` (equivalent to `amplitude * sqrt(f)`) before smoothing in both modes. Desktop RTA compensates after smoothing and uses a 1 kHz reference, so absolute levels may differ. Do not silently change normalization to make graphs match visually.
4. Gaussian smoothing follows desktop `audioanalysis/smoothing.py`: FWHM in octaves, −30 dB support, `1/f` integration weights and clipping to the measurement band. Empty sub-resolution bands use the −200 dB floor.
5. RTA centers follow the IEC 61260 base-10 grid around 1 kHz. Even denominators have a half-step offset. Include bands whose edges intersect the measurement range, retaining the nominal 20 Hz band. FWHM is grid spacing, `log2(10^(0.3 / fraction))`, independent of the selected band. This is Gaussian smoothing on a standard grid, not an IEC-compliant filter bank.
6. Welch averages complete overlapping frames in linear power for live preview. Spectrum always stores PCM and finishes with a periodogram of the entire actual capture, with a corresponding full-length Gaussian plan and rectangular temporal window. Early stop before a complete Welch frame still yields a final result if at least two samples were captured.
7. Pink noise uses IFFT with desktop fourth-order band envelopes (−0.5 dB at requested edges) and peak normalization. `GENERATOR_FADE_SECONDS` is 0.5, not a UI setting. Pink fades at session boundaries, never at repeated-period boundaries. RTA noise period equals its window width. Chirp extends the log sweep by 0.5 s on each side of the working duration and fades those extensions linearly. Unlike the desktop rejection of an above-Nyquist extension, Android holds the tail frequency at 0.49 × sample rate once reached, with continuous phase, so the default 20 kHz band remains usable at 48 kHz. Full Spectrum capture includes both fades; progress subtracts the initial fade. Manual stop drains the playback fade before releasing the track.

`GaussianPlan` fuses normalized Gaussian weights, the Jacobian and de-pink into reusable rows. `PlanKey` includes FFT length, sample rate, band, point count, FWHM, temporal-window selection and octave fraction. `PlanCache` is an LRU capped at four plans / 64 MiB, with a 48 MiB per-plan guard. Build expensive plans before opening the microphone; preserve cache reuse across measurements. FFT objects, temporal windows and work arrays are reused within a measurement.

Capture, analysis and playback run on separate workers. A bounded PCM queue separates recording from analysis; report overload instead of silently dropping samples. Online Welch uses ring buffers and running power averages. Preserve orderly resource release and stop behavior on all paths.

For a full-recording Spectrum plan exceeding the 48 MiB weight budget, evaluate the exact Gaussian rows on demand without retaining a coefficient matrix. This bounds memory for long recordings; it trades final-analysis time for memory. RTA/Welch continue caching their weights. `Measurement` prepares both stream and final analyzers before opening audio.

## Settings and bridge consistency

Keep `src/model.ts`, `src/settings-controls.ts`, native `Settings.kt` and `SpectrumPlugin.kt` parsing consistent when changing settings. Validate dependent values such as hop/window size and Welch window/recording duration.

Settings use `bm-settings-v2` local storage. Loading migrates known v1 fields and maps old RTA counts 32/64/128 to fractions 3/6/12. Removed generator type/gain fields are not carried over. Preserve users' saved settings during development and device checks.

The bridge exposes `start`, `stop`, `getState` and `state`/`plot` events. Internal elapsed/frame/cache counters support processing and diagnostics; do not restore the removed status row merely because those values exist. `getState` also exposes the requested screen-awake flag for device checks.

## Build and verification

Use the lockfile. Android configuration: minimum SDK 24, compile/target SDK 36, JVM/JDK 21. The PowerShell helper detects `.tools/java/` and `.tools/android-sdk/`; otherwise use `JAVA_HOME` and `android/local.properties`.

```powershell
npm ci
npm run sync
./scripts/android.ps1 -Tasks ':app:assembleDebug',':app:testDebugUnitTest',':app:lintDebug'
```

`npm run sync` performs TypeScript checking, builds Vite output and copies it into Android. Run it after UI changes before building/installing an APK. Native-only changes can use Gradle directly through the helper. Documentation-only changes do not require an Android rebuild.

On the current machine, ADB is at `C:/platform-tools/adb.exe`:

```powershell
& C:/platform-tools/adb.exe devices
& C:/platform-tools/adb.exe install -r android/app/build/outputs/apk/debug/app-debug.apk
& C:/platform-tools/adb.exe shell am start -n com.bm.spectrum/.MainActivity
node scripts/device-smoke.mjs
```

The smoke script needs the debug app in the foreground and microphone permission granted. It briefly emits both signals at peak 0.9. It checks real capture, generator arming/shutdown, octave grids, cache reuse, cursor, Y pan, settings and background stop. Reports/screenshots go into ignored `test-results/`. `ADB` overrides the script's executable path. Restore device settings after orientation or other system tests.

Numerical tests compare Kotlin results with NumPy/SciPy and desktop Gaussian references: odd/even FFT sizes, DC/Nyquist, reused buffers, streaming Welch overlap and octave/cache behavior. Regenerate fixtures only for an intentional DSP contract change, never to hide a regression:

```powershell
& D:/Code/Spectrum/venv/Scripts/python.exe scripts/reference_fixtures.py D:/Code/Spectrum
```

v0.2 adds regression tests for final periodograms with/without Welch, early stop, ±6.02 dB amplitude scaling, generator-dependent RTA windows, extended sweeps and bounded long-recording smoothing. Device smoke checks cover final analysis and settings visibility in both landscape rotations. Physical external-input/output validation and VS Code diagnostics were explicitly deferred by the user.

`.github/workflows/android.yml` builds/tests/lints pushes and pull requests, retaining APK/report artifacts. Tags matching the Android versionName publish a GitHub Release using `releases/<tag>.md`. Trusted builds restore the existing development key from the repository secret `ANDROID_DEBUG_KEYSTORE`, select it explicitly through `BM_DEBUG_KEYSTORE`, and verify the APK certificate against the v0.1 certificate before packaging. Never rely on a runner's default debug key location; never commit or log the key. Releases remain debug builds. PR builds use disposable debug keys and cannot publish releases. Update Android versionCode/versionName and npm package version before a new release.

If ADB screenshots are black, check display/lock state before concluding the UI failed. The phone has responded through WebView while its display was off. WindowManager's screen-hold report can lag the app's requested flag; distinguish them when diagnosing shutdown.

## Documentation maintenance

Keep README focused on users, with repository-relative screenshot links. Keep technical decisions here. Track unfinished work in TODO with unchecked boxes; distinguish numerical verification from hardware validation and do not mark unverified IDE issues as resolved.

The user may edit files while a task is running. Re-read each file immediately before editing, apply targeted changes, and preserve newly added content. Never reconstruct TODO from an earlier snapshot. If concurrent changes conflict with a planned edit, merge them rather than overwriting the file.
