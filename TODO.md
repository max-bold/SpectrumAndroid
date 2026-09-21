# Roadmap

## v0.1 — completed

- [x] Bind generator type to measurement mode: Spectrum → chirp, RTA → IFFT pink noise. Remove generator selection from settings.
- [x] Start and stop the generator with measurement. Use the main generator button only to arm playback.
- [x] Place the dB axis label vertically on the left and Hz below the graph, centered.
- [x] Fix generator peak amplitude at 0.9 (approximately −1 dBFS); use Android media volume for loudness.
- [x] Add a vertical cursor showing the selected frequency and level.
- [x] Overlay the application logo from `logo/` at the upper left of the graph.
- [x] Place landscape controls vertically over the upper-right part of the graph.
- [x] Maximize graph size and respect Android system-bar/cutout insets.
- [x] Include the selected mode in the Y-axis label: `RTA dB` / `Spectrum dB`.
- [x] Replace numeric settings with sliders using provisional ranges. Final range agreement is tracked in v0.2.
- [x] Make settings more compact, reduce vertical spacing and remove separators within groups. Use two columns in landscape.
- [x] Keep the Android screen awake while measuring.
- [x] Remove the persistent status row and sample/frame counts from the main screen.
- [x] Remove the `de-pink` caption while retaining the correction in DSP.
- [x] Add approximate Spectrum sweep progress along the bottom of the graph.
- [x] Highlight the selected measurement band when it differs from 20 Hz–20 kHz.
- [x] Replace arbitrary RTA point counts with standard 1/3-, 1/6- and 1/12-octave frequency grids.
- [x] Verify generator samples and DSP numerically against reference fixtures. Physical output validation remains in v0.2.
- [x] Document the project, screenshots, structure and implementation decisions in README and AGENTS.

## v0.2 — remaining work

- [ ] In Spectrum mode, recompute the final spectrum from the entire recording using a periodogram after capture finishes, including when online Welch is enabled. Rebuild the smoothing windows for the full recording length; currently the Welch result remains.
- [ ] Fix scrolling in landscape settings: with Spectrum selected, the mode buttons can disappear above the top edge. See `screenshots/Screenshot_20260921_133531.jpg` and `screenshots/Screenshot_20260921_133536.jpg`.
- [ ] Express the online Welch window length and hop size in seconds.
- [ ] Select the RTA temporal window according to generator state: use boxcar when the generator is enabled so parts of the spectrum are not lost; use Hann when it is disabled (for example, when measuring music) to reduce recording-boundary artifacts.
- [ ] Verify the dB scale: doubling or halving signal amplitude must change the level by approximately ±6 dB, following `20 * log10(X)`.
- [ ] Make the graph logo slightly smaller and more transparent; if feasible, tint it to match the graph color.
- [ ] Add generator fade-in/out and extend the chirp frequency range as in the Windows version. Use a 0.5 s fade, defined as a parameter in code rather than exposed in settings.
- [ ] Add README positioning that distinguishes the project from similar apps: professional, verified measurement algorithms in a user-friendly interface. Explain the intended advantages of chirp measurements over noise, including repeatability and greater frequency resolution.
- [ ] Add a README note that the software is intended for professional use and is of little practical use without an external measurement microphone, such as Behringer UMC8000-U, MiniDSP UMIK-1 or Dayton Audio iMM-6C. Set clear expectations to avoid complaints about meaningless measurements with the built-in microphone.
- [ ] Configure CI/CD with GitHub Actions.
- [ ] Publish a GitHub release.
- [ ] Verify measured spectrum slope using an external input, including the effect of de-pink correction.
- [ ] Verify both generators' actual output spectra through an external measurement path. Numerical fixture tests alone do not validate the Android playback chain.
- [ ] Agree final numeric slider ranges and steps, then update UI controls and matching TypeScript/Kotlin validation.
- [ ] Investigate and resolve reported VS Code diagnostics. TypeScript builds and Android checks pass, but the IDE-specific issue has not been reproduced or confirmed fixed.
