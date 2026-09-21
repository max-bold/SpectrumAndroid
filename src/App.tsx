import { useEffect, useRef, useState } from 'react';
import { Capacitor } from '@capacitor/core';
import { App as NativeApp } from '@capacitor/app';
import Chart from './Chart';
import { Spectrum, loadSettings, validate, SETTINGS_KEY, type Settings, type EngineState, type PlotData } from './model';
import logo from '../logo/White@4x.png';
import { sliderSpec, updateNumeric } from './settings-controls';

const initial: EngineState = {running:false,generating:false,error:'',elapsed:0,frames:0,cacheBuilds:0};
function Icon({kind}:{kind:string}) {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {kind==='play' ? <path d="M8 5l11 7-11 7z" fill="currentColor" stroke="none"/> : kind==='stop' ? <rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none"/> : kind==='generator' ? <path d="M2 12c4-17 6 17 10 0s6 17 10 0"/> : <><circle cx="12" cy="12" r="3"/><path d="M10 3h4l1 3 3-1 2 3-2 3 2 3-2 3-3-1-1 3h-4l-1-3-3 1-2-3 2-3-2-3 2-3 3 1z"/></>}
  </svg>;
}
export default function App() {
  const [settings,setSettings]=useState(loadSettings);
  const [draft,setDraft]=useState(settings);
  const [showSettings,setShowSettings]=useState(false);
  const [state,setState]=useState(initial);
  const [data,setData]=useState<PlotData|null>(null);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const mounted=useRef(true);
  useEffect(()=>{
    if(!Capacitor.isNativePlatform()) return;
    const listener=NativeApp.addListener('backButton',()=>{
      if(showSettings) {setShowSettings(false);setError('');}
      else void Spectrum.stop().finally(()=>NativeApp.minimizeApp());
    });
    return ()=>{void listener.then(h=>h.remove());};
  },[showSettings]);
  useEffect(()=>{
    mounted.current=true;
    if(!Capacitor.isNativePlatform()) return;
    const stateListener=Spectrum.addListener('state',s=>{if(mounted.current) setState(s);});
    const plotListener=Spectrum.addListener('plot',p=>{if(mounted.current) setData(p);});
    Spectrum.getState().then(s=>{if(mounted.current) setState(s);}).catch(e=>setError(String(e)));
    return ()=>{mounted.current=false; void stateListener.then(h=>h.remove()); void plotListener.then(h=>h.remove());};
  },[]);
  async function action(fn:()=>Promise<EngineState>) {
    if(busy) return;
    if(!Capacitor.isNativePlatform()) {setError('Open the Android app to record or generate audio.');return;}
    setBusy(true); setError('');
    try {setState(await fn());} catch(e) {setError(e instanceof Error ? e.message : String(e));}
    finally {setBusy(false);}
  }
  async function openSettings() {
    if(busy) return;
    if(state.running || state.generating) await action(()=>Spectrum.stop());
    setDraft({...settings}); setShowSettings(true); setError('');
  }
  function save() {
    const problem=validate(draft); if(problem) {setError(problem);return;}
    localStorage.setItem(SETTINGS_KEY,JSON.stringify(draft));
    if(JSON.stringify(settings)!==JSON.stringify(draft)) setData(null);
    setSettings({...draft}); setShowSettings(false); setError('');
  }
  function field(key:keyof Settings,label:string,unit:string) {
    const spec=sliderSpec(key,draft),value=draft[key] as number;
    // Keep exact sample counts in storage/bridge; expose both Welch controls in seconds.
    const displayValue=key==='welchSize'||key==='welchHop'?Number((value/48000).toPrecision(5)):Number(value.toFixed(3));
    const position=spec.log?Math.log(value/spec.min)/Math.log(spec.max/spec.min)*1000:spec.power?Math.log2(value):value;
    return <label className="slider-field"><span className="slider-heading"><span>{label}</span><span className="slider-value">{displayValue} <small>{unit}</small></span></span>
      <input aria-label={label} aria-valuetext={`${displayValue} ${unit}`} type="range" min={spec.log?0:spec.min} max={spec.log?1000:spec.max} step={spec.log?1:spec.step} value={position} onChange={e=>{
        const raw=Number(e.target.value);
        let v=spec.log?spec.min*(spec.max/spec.min)**(raw/1000):spec.power?2**raw:raw;
        if(spec.log) v=v>=1000?Math.round(v/10)*10:Math.round(v);
        setDraft(updateNumeric(draft,key,v));
      }}/></label>;
  }
  function toggleGenerator() {
    const next={...settings,generatorEnabled:!settings.generatorEnabled};
    setSettings(next);localStorage.setItem(SETTINGS_KEY,JSON.stringify(next));
  }
  return <main>
    <div className="chart-area"><Chart data={data} settings={settings} running={state.running} elapsed={state.elapsed}/></div>
    <div className="brand-logo" role="img" aria-label="BM Spectrum" style={{maskImage:`url("${logo}")`,WebkitMaskImage:`url("${logo}")`}}/>
    <nav className="controls" aria-label="Measuring">
      <button className={`icon-button primary ${state.running ? 'active' : ''}`} disabled={busy} aria-busy={busy} aria-label={state.running?'Stop measurement':'Start measurement'} title="Play / Stop" onClick={()=>void action(()=>{if(state.running) return Spectrum.stop();setData(null);return Spectrum.start({settings});})}><Icon kind={state.running?'stop':'play'}/></button>
      <button className={`icon-button ${settings.generatorEnabled?'active':''}`} disabled={busy} aria-label="Use generator" aria-pressed={settings.generatorEnabled} title="Use generator with measurement" onClick={toggleGenerator}><Icon kind="generator"/></button>
      <button className="icon-button" disabled={busy} aria-label="Settings" onClick={()=>void openSettings()}><Icon kind="settings"/></button>
    </nav>
    {(error||state.error) && !showSettings && <div className="error" role="alert">{error||state.error}</div>}
    {showSettings && <section className="settings" aria-label="Settings"><div className="settings-header"><button onClick={()=>{setShowSettings(false);setError('');}} aria-label="Back">←</button><h1>Settings</h1><button className="save" onClick={save}>Done</button></div>
      <div className="mode-selector"><div className="segmented" aria-label="Mode">{(['RTA','Spectrum'] as const).map(mode=><button key={mode} className={draft.mode===mode?'selected':''} aria-pressed={draft.mode===mode} onClick={()=>setDraft({...draft,mode})}>{mode}</button>)}</div></div>
      <div className="settings-body" key={draft.mode}>
        <div className="settings-group"><h2>Band</h2>{field('low','Low frequency','Hz')}{field('high','High frequency','Hz')}</div>
        <div className="settings-group"><h2>{draft.mode}</h2>
        {draft.mode==='RTA' ? <>
          {field('rtaWidth','Window width','s')}{field('rtaHop','Hop','s')}
          <label className="field"><span>Octave bands</span><select aria-label="Octave bands" value={draft.rtaFraction} onChange={e=>setDraft({...draft,rtaFraction:Number(e.target.value)})}>{[3,6,12].map(n=><option key={n} value={n}>1/{n} octave</option>)}</select></label>
        </> : <>
          {field('duration','Duration','s')}{field('smoothing','Smoothing','oct')}{field('spectrumPoints','Point count','')}
          <label className="field"><span>Online Welch</span><input aria-label="Online Welch" className="switch" type="checkbox" checked={draft.onlineWelch} onChange={e=>setDraft(updateNumeric({...draft,onlineWelch:e.target.checked},'duration',draft.duration))}/></label>
          {draft.onlineWelch && <>{field('welchSize','Welch window','s')}{field('welchHop','Welch hop','s')}</>}
        </>}</div>
        {error && <p className="settings-error" role="alert">{error}</p>}
      </div>
    </section>}
  </main>;
}
