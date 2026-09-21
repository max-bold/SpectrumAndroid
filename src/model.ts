import { registerPlugin, type PluginListenerHandle } from '@capacitor/core';
export type Settings = {
  mode: 'Spectrum' | 'RTA'; low: number; high: number; duration: number;
  smoothing: number; spectrumPoints: number; onlineWelch: boolean; welchSize: number;
  welchHop: number; rtaWidth: number; rtaHop: number; rtaFraction: number; generatorEnabled: boolean;
};
export const defaults: Settings = {
  mode: 'RTA', low: 20, high: 20000, duration: 5, smoothing: 0.3,
  spectrumPoints: 256, onlineWelch: true, welchSize: 8192, welchHop: 4096,
  rtaWidth: 3, rtaHop: 0.1, rtaFraction: 3, generatorEnabled: false,
};
export type EngineState = { running: boolean; generating: boolean; error: string; elapsed: number; sweepLead: number; frames: number; cacheBuilds: number };
export type PlotData = { frequency: number[]; db: number[]; elapsed: number; frames: number; analysisMs: number };
interface SpectrumPlugin {
  start(args: { settings: Settings }): Promise<EngineState>;
  stop(): Promise<EngineState>;
  getState(): Promise<EngineState>;
  addListener(event: 'state', listener: (state: EngineState) => void): Promise<PluginListenerHandle>;
  addListener(event: 'plot', listener: (plot: PlotData) => void): Promise<PluginListenerHandle>;
}
export const Spectrum = registerPlugin<SpectrumPlugin>('Spectrum');
export function validate(s: Settings): string {
  if (Object.entries(defaults).some(([key,value]) => typeof value === 'number' && (typeof s[key as keyof Settings] !== 'number' || !Number.isFinite(s[key as keyof Settings])))) return 'Fill in all numeric fields';
  if (typeof s.onlineWelch !== 'boolean' || typeof s.generatorEnabled !== 'boolean') return 'Invalid Welch setting';
  if (s.mode !== 'RTA' && s.mode !== 'Spectrum') return 'Unknown mode';
  if (s.low < 20 || s.high > 20000 || s.high <= s.low) return 'Band: 20–20000 Hz; low must be below high';
  if (s.duration < 0.5 || s.duration > 30) return 'Duration: 0.5–30 s';
  if (s.smoothing < 0.03 || s.smoothing > 2) return 'Smoothing: 0.03–2 oct';
  if (!Number.isInteger(s.spectrumPoints) || s.spectrumPoints < 32 || s.spectrumPoints > 1024) return 'Spectrum: 32–1024 points';
  if (!Number.isInteger(s.welchSize) || s.welchSize < 1024 || s.welchSize > 262144 || (s.welchSize & (s.welchSize - 1)) !== 0) return 'Welch size: power of two, 1024–262144';
  if (!Number.isInteger(s.welchHop) || s.welchHop < 1 || s.welchHop > s.welchSize) return 'Welch hop: 1–window size';
  if (s.mode === 'Spectrum' && s.onlineWelch && s.welchSize > Math.round(s.duration * 48000)) return 'Welch window exceeds recording duration';
  if (s.rtaWidth < 0.1 || s.rtaWidth > 10) return 'RTA window: 0.1–10 s';
  if (s.rtaHop < 0.02 || s.rtaHop > s.rtaWidth) return 'RTA hop: 0.02 s–window width';
  if (![3,6,12].includes(s.rtaFraction)) return 'RTA: 1/3, 1/6 or 1/12 octave';
  return '';
}
export const SETTINGS_KEY = 'bm-settings-v2';
export function loadSettings(): Settings {
  try {
    const saved = JSON.parse(localStorage.getItem(SETTINGS_KEY) || localStorage.getItem('bm-settings-v1') || '{}');
    const s = Object.fromEntries(Object.entries(defaults).map(([key,value])=>[key,saved[key] ?? value])) as Settings;
    if (!saved.rtaFraction && saved.rtaPoints) s.rtaFraction = saved.rtaPoints <= 32 ? 3 : saved.rtaPoints <= 64 ? 6 : 12;
    return validate(s) ? {...defaults} : s;
  } catch { return {...defaults}; }
}
