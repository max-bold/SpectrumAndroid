import { registerPlugin, type PluginListenerHandle } from '@capacitor/core';
export type Settings = {
  mode: 'Spectrum' | 'RTA'; low: number; high: number; duration: number;
  smoothing: number; spectrumPoints: number; onlineWelch: boolean; welchSize: number;
  welchHop: number; rtaWidth: number; rtaHop: number; rtaFraction: number; generatorEnabled: boolean;
  inputDeviceId: number; referenceDeviceId: number; calibrationId: string;
};
export const defaults: Settings = {
  mode: 'RTA', low: 20, high: 20000, duration: 5, smoothing: 0.3,
  spectrumPoints: 256, onlineWelch: true, welchSize: 8192, welchHop: 4096,
  rtaWidth: 3, rtaHop: 0.1, rtaFraction: 3, generatorEnabled: false,
  inputDeviceId: -1, referenceDeviceId: -1, calibrationId: '',
};
export type InputDevice = {id:number; name:string; type:number; builtIn:boolean; gainControl:boolean};
export type Calibration = {id:string; inputDeviceId:number; sampleRate:number; audioSource:number;
  frequencies:number[]; correctionDb:number[]; fMin:number; fMax:number; averageRepeatability:number;
  phoneSnrDb:number; referenceSnrDb:number; responseDb?:number[]; sensitivityDbSpl?:number|null;
  spectra?:{frequencies:number[];phoneNoiseDb:number[];referenceNoiseDb:number[];phoneSignalDb:number[];referenceSignalDb:number[]}};
export const CALIBRATIONS_KEY='bm-calibrations-v2';
function validSpectra(value:unknown):boolean {
  if(value==null)return true;
  const s=value as Record<string,unknown>;
  return ['frequencies','phoneNoiseDb','referenceNoiseDb','phoneSignalDb','referenceSignalDb'].every(key=>
    Array.isArray(s[key]) && s[key].length===128 && s[key].every(Number.isFinite));
}
export function parseCalibrations(json:string):Calibration[] {
  try {
    const values=JSON.parse(json);
    return Array.isArray(values)?values.filter((v:Calibration)=>v && typeof v.id==='string' && Array.isArray(v.frequencies) && v.frequencies.length===128 &&
      Array.isArray(v.correctionDb) && v.correctionDb.length===128 && Number.isInteger(v.inputDeviceId) && v.inputDeviceId>=0 &&
      Number.isInteger(v.sampleRate) && v.sampleRate>0 && Number.isInteger(v.audioSource) &&
      Number.isFinite(v.fMin) && Number.isFinite(v.fMax) && v.fMin>=20 && v.fMax<=Math.min(20000,v.sampleRate/2) && v.fMin<v.fMax &&
      v.frequencies.every((f,i)=>Number.isFinite(f) && f>=v.fMin && f<=v.fMax && (i===0 || f>v.frequencies[i-1])) &&
      v.correctionDb.every(x=>Number.isFinite(x) && Math.abs(x)<=20) &&
      (v.responseDb===undefined || (Array.isArray(v.responseDb) && v.responseDb.length===128 && v.responseDb.every(x=>Number.isFinite(x)))) &&
      validSpectra(v.spectra)).slice(-1):[];
  } catch{return [];}
}
export function loadCalibrations():Calibration[] {
  const curves=parseCalibrations(localStorage.getItem(CALIBRATIONS_KEY)||'[]');
  localStorage.setItem(CALIBRATIONS_KEY,JSON.stringify(curves));
  localStorage.removeItem('bm-calibrations-v1');
  return curves;
}
export function clampToCalibration(s:Settings,c:Calibration):Settings {
  const low=Math.max(s.low,c.fMin), high=Math.min(s.high,c.fMax);
  return {...s,low:low<high?low:c.fMin,high:low<high?high:c.fMax};
}
export type EngineState = { running: boolean; generating: boolean; error: string; elapsed: number; sweepLead: number; frames: number; cacheBuilds: number; calibrationPhase?: string };
export type InputLevel = {dbfs:number};
export type PlotData = { frequency: number[]; db: number[]; elapsed: number; frames: number; analysisMs: number };
interface SpectrumPlugin {
  start(args: { settings: Settings; calibration?: Calibration }): Promise<EngineState>;
  listInputs(): Promise<{devices:InputDevice[];defaultInputId:number}>;
  calibrate(args:{phoneId:number;referenceId:number;low:number;high:number}):Promise<Omit<Calibration,'id'>>;
  inputGain(args:{deviceId:number;gainDb?:number}):Promise<{gainDb:number;minDb:number;maxDb:number}>;
  stop(): Promise<EngineState>;
  getState(): Promise<EngineState>;
  addListener(event: 'state', listener: (state: EngineState) => void): Promise<PluginListenerHandle>;
  addListener(event: 'plot', listener: (plot: PlotData) => void): Promise<PluginListenerHandle>;
  addListener(event: 'level', listener: (level: InputLevel) => void): Promise<PluginListenerHandle>;
}
export const Spectrum = registerPlugin<SpectrumPlugin>('Spectrum');
export function validate(s: Settings): string {
  if (Object.entries(defaults).some(([key,value]) => typeof value === 'number' && (typeof s[key as keyof Settings] !== 'number' || !Number.isFinite(s[key as keyof Settings])))) return 'Fill in all numeric fields';
  if (typeof s.onlineWelch !== 'boolean' || typeof s.generatorEnabled !== 'boolean') return 'Invalid Welch setting';
  if (!Number.isInteger(s.inputDeviceId) || s.inputDeviceId < -1 || typeof s.calibrationId !== 'string') return 'Invalid input selection';
  if (s.mode !== 'RTA' && s.mode !== 'Spectrum') return 'Unknown mode';
  if (s.low < 20 || s.high > 20000 || s.high <= s.low) return 'Band: 20–20000 Hz; low must be below high';
  if (s.duration < 0.5 || s.duration > 30) return 'Duration: 0.5–30 s';
  if (s.smoothing < 0.1 || s.smoothing > 1) return 'Smoothing: 0.1–1 oct';
  if (!Number.isInteger(s.spectrumPoints) || s.spectrumPoints < 32 || s.spectrumPoints > 1024) return 'Spectrum: 32–1024 points';
  if (!Number.isInteger(s.welchSize) || s.welchSize < 1024 || s.welchSize > 262144 || (s.welchSize & (s.welchSize - 1)) !== 0) return 'Welch size: power of two, 1024–262144';
  if (!Number.isInteger(s.welchHop) || s.welchHop < 1 || s.welchHop > s.welchSize) return 'Welch hop: 1–window size';
  if (s.mode === 'Spectrum' && s.onlineWelch && s.welchSize > Math.round(s.duration * 48000)) return 'Welch window exceeds recording duration';
  if (s.rtaWidth < 0.1 || s.rtaWidth > 10) return 'RTA window: 0.1–10 s';
  if (s.rtaHop < 0.02 || s.rtaHop > s.rtaWidth) return 'RTA hop: 0.02 s–window width';
  if (![3,6,12,512,1024].includes(s.rtaFraction)) return 'RTA: 1/3, 1/6, 1/12, 512/0.3 or 1024/0.15';
  return '';
}
export const SETTINGS_KEY = 'bm-settings-v2';
export function loadSettings(): Settings {
  try {
    const saved = JSON.parse(localStorage.getItem(SETTINGS_KEY) || localStorage.getItem('bm-settings-v1') || '{}');
    const s = Object.fromEntries(Object.entries(defaults).map(([key,value])=>[key,saved[key] ?? value])) as Settings;
    if (!saved.rtaFraction && saved.rtaPoints) s.rtaFraction = saved.rtaPoints <= 32 ? 3 : saved.rtaPoints <= 64 ? 6 : 12;
    if (s.rtaFraction === 128) s.rtaFraction = 12;
    if (Number.isFinite(s.smoothing)) s.smoothing = Math.max(0.1,Math.min(1,s.smoothing));
    if(s.calibrationId && !loadCalibrations().some(c=>c.id===s.calibrationId)) s.calibrationId='';
    return validate(s) ? {...defaults} : s;
  } catch { return {...defaults}; }
}
