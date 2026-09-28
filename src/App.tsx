import { useEffect, useRef, useState } from 'react';
import { Capacitor } from '@capacitor/core';
import { App as NativeApp } from '@capacitor/app';
import Chart from './Chart';
import { Spectrum, loadSettings, loadCalibrations, clampToCalibration, validate, SETTINGS_KEY, CALIBRATIONS_KEY, type Calibration, type InputDevice, type Settings, type EngineState, type PlotData } from './model';
import logo from '../logo/White@4x.png';
import { sliderSpec, updateNumeric } from './settings-controls';

const initial: EngineState = {running:false,generating:false,error:'',elapsed:0,sweepLead:0,frames:0,cacheBuilds:0};
const calibrationPhaseText:Record<string,string> = {
  'reference-noise':'1/4 · Reference background noise', 'phone-noise':'2/4 · Built-in background noise',
  'reference-signal':'3/4 · Reference with signal', 'phone-signal':'4/4 · Built-in with signal',
  'analyzing':'Analyzing recordings',
};
function Icon({kind}:{kind:string}) {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {kind==='play' ? <path d="M8 5l11 7-11 7z" fill="currentColor" stroke="none"/> : kind==='stop' ? <rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none"/> : kind==='generator' ? <path d="M2 12c4-17 6 17 10 0s6 17 10 0"/> : <><circle cx="12" cy="12" r="3"/><path d="M10 3h4l1 3 3-1 2 3-2 3 2 3-2 3-3-1-1 3h-4l-1-3-3 1-2-3 2-3-2-3 2-3 3 1z"/></>}
  </svg>;
}
export default function App() {
  const [settings,setSettings]=useState(loadSettings);
  const [draft,setDraft]=useState(settings);
  const [showSettings,setShowSettings]=useState(false);
  const [settingsPage,setSettingsPage]=useState<'analyzer'|'calibration'>('analyzer');
  const [state,setState]=useState(initial);
  const [data,setData]=useState<PlotData|null>(null);
  const [inputLevel,setInputLevel]=useState(-80);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [devices,setDevices]=useState<InputDevice[]>([]);
  const [defaultInputId,setDefaultInputId]=useState(-1);
  const [calibrations,setCalibrations]=useState<Calibration[]>(loadCalibrations);
  const [gainBusy,setGainBusy]=useState(false);
  const builtInId=devices.find(d=>d.builtIn)?.id??-1;
  const referenceInputs=devices.filter(d=>!d.builtIn);
  const selectedReferenceId=referenceInputs.find(d=>d.id===draft.inputDeviceId)?.id??referenceInputs.find(d=>d.id===draft.referenceDeviceId)?.id??(referenceInputs.length===1?referenceInputs[0].id:-1);
  const latestCalibration=calibrations.at(-1);
  const selectedInputId=draft.inputDeviceId<0?defaultInputId:draft.inputDeviceId;
  const selectedInput=devices.find(d=>d.id===selectedInputId);
  const mounted=useRef(true);
  useEffect(()=>{
    if(!Capacitor.isNativePlatform()) return;
    const listener=NativeApp.addListener('backButton',()=>{
      if(gainBusy) return;
      if(busy) {void Spectrum.stop();return;}
      if(showSettings && settingsPage==='calibration') {setSettingsPage('analyzer');setError('');}
      else if(showSettings) {setShowSettings(false);setError('');}
      else void Spectrum.stop().finally(()=>NativeApp.minimizeApp());
    });
    return ()=>{void listener.then(h=>h.remove());};
  },[showSettings,settingsPage,busy,gainBusy]);
  useEffect(()=>{
    mounted.current=true;
    if(!Capacitor.isNativePlatform()) return;
    const stateListener=Spectrum.addListener('state',s=>{if(mounted.current) setState(s);});
    const plotListener=Spectrum.addListener('plot',p=>{if(mounted.current) setData(p);});
    const levelListener=Spectrum.addListener('level',p=>{if(mounted.current) setInputLevel(p.dbfs);});
    Spectrum.getState().then(s=>{if(mounted.current) setState(s);}).catch(e=>setError(String(e)));
    return ()=>{mounted.current=false; void stateListener.then(h=>h.remove()); void plotListener.then(h=>h.remove()); void levelListener.then(h=>h.remove());};
  },[]);
  async function action(fn:()=>Promise<EngineState>) {
    if(busy) return;
    if(!Capacitor.isNativePlatform()) {setError('Open the Android app to record or generate audio.');return;}
    setBusy(true); setError('');
    try {setState(await fn());} catch(e) {setError(e instanceof Error ? e.message : String(e));}
    finally {setBusy(false);}
  }
  function openSettings() {
    if(busy) return;
    setDraft({...settings}); setSettingsPage('analyzer'); setShowSettings(true); setError('');
    if(Capacitor.isNativePlatform()) void Spectrum.listInputs().then(r=>{
      setDevices(r.devices);
      setDefaultInputId(r.defaultInputId);
    }).catch(e=>setError(String(e)));
    if(state.running || state.generating) void action(()=>Spectrum.stop());
  }
  function save() {
    if(draft.inputDeviceId>=0 && devices.length && !devices.some(d=>d.id===draft.inputDeviceId)) {setError('Selected recording device is disconnected');return;}
    const selected=calibrations.find(c=>c.id===draft.calibrationId);
    if(draft.calibrationId && !selected) {setError('Calibration does not match the selected input');return;}
    const saved=selected && selected.inputDeviceId===selectedInputId?clampToCalibration(draft,selected):draft;
    const problem=validate(saved); if(problem) {setError(problem);return;}
    localStorage.setItem(SETTINGS_KEY,JSON.stringify(saved));
    if(JSON.stringify(settings)!==JSON.stringify(saved)) setData(null);
    setSettings({...saved}); setShowSettings(false); setError('');
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
  function chooseCalibration(id:string) {
    const curve=calibrations.find(c=>c.id===id);
    const next={...draft,calibrationId:id};
    setDraft(curve && curve.inputDeviceId===selectedInputId?clampToCalibration(next,curve):next);
  }
  async function runCalibration() {
    if(busy || gainBusy) return;
    if(builtInId<0) {setError('Built-in microphone is unavailable');return;}
    if(selectedReferenceId<0 || selectedReferenceId===builtInId) {setError(referenceInputs.length?'Select the external microphone under Input in Settings.':'External microphone is not connected. Connect it and select it under Input in Settings.');return;}
    setBusy(true);setError('');
    try {
      const result=await Spectrum.calibrate({phoneId:builtInId,referenceId:selectedReferenceId,low:20,high:20000});
      const curve:Calibration={...result,id:`${builtInId}-${Date.now()}`};
      const next=[curve];setCalibrations(next);
      localStorage.setItem(CALIBRATIONS_KEY,JSON.stringify(next));
      if(settings.calibrationId) {const next={...settings,calibrationId:curve.id};const updated=settings.inputDeviceId===builtInId?clampToCalibration(next,curve):next;setSettings(updated);localStorage.setItem(SETTINGS_KEY,JSON.stringify(updated));}
      setDraft(s=>{const next={...s,calibrationId:s.calibrationId?curve.id:''};return next.calibrationId && selectedInputId===builtInId?clampToCalibration(next,curve):next;});
    } catch(e) {setError(e instanceof Error?e.message:String(e));}
    finally {setBusy(false);}
  }
  const selectedCalibration=calibrations.find(c=>c.id===settings.calibrationId);
  return <main>
    <div className="chart-area"><Chart data={data} settings={settings} running={state.running} elapsed={state.elapsed} sweepLead={state.sweepLead}/></div>
    <InputLevelMeter dbfs={inputLevel} />
    <div className="brand-logo" role="img" aria-label="BM Spectrum" style={{maskImage:`url("${logo}")`,WebkitMaskImage:`url("${logo}")`}}/>
    <nav className="controls" aria-label="Measuring">
      <button className={`icon-button primary ${state.running ? 'active' : ''}`} disabled={busy} aria-busy={busy} aria-label={state.running?'Stop measurement':'Start measurement'} title="Play / Stop" onClick={()=>void action(async()=>{if(state.running) return Spectrum.stop();if(settings.calibrationId && !selectedCalibration) throw new Error('Selected calibration is unavailable');const inputs=await Spectrum.listInputs();const inputId=settings.inputDeviceId<0?inputs.defaultInputId:settings.inputDeviceId;const curve=inputs.devices.find(d=>d.id===inputId)?.builtIn?selectedCalibration:undefined;const next=curve && curve.inputDeviceId===inputId?clampToCalibration({...settings,inputDeviceId:inputId},curve):{...settings,inputDeviceId:inputId};setSettings(next);localStorage.setItem(SETTINGS_KEY,JSON.stringify(next));setData(null);return Spectrum.start({settings:next,calibration:curve});})}><Icon kind={state.running?'stop':'play'}/></button>
      <button className={`icon-button ${settings.generatorEnabled?'active':''}`} disabled={busy} aria-label="Use generator" aria-pressed={settings.generatorEnabled} title="Use generator with measurement" onClick={toggleGenerator}><Icon kind="generator"/></button>
      <button className="icon-button" disabled={busy} aria-label="Settings" onClick={openSettings}><Icon kind="settings"/></button>
    </nav>
    {(error||state.error) && !showSettings && <div className="error" role="alert">{error||state.error}</div>}
    {showSettings && <section className="settings" aria-label={settingsPage==='calibration'?'Calibration settings':'Settings'}><div className="settings-header"><button disabled={gainBusy} onClick={()=>{if(busy){void Spectrum.stop();return;}if(settingsPage==='calibration')setSettingsPage('analyzer');else setShowSettings(false);setError('');}} aria-label={busy?'Cancel calibration':'Back'}>{busy?'×':'←'}</button><h1>{settingsPage==='calibration'?'Calibration':'Settings'}</h1><button className="save" disabled={busy || gainBusy} onClick={save}>Done</button></div>
      {settingsPage==='analyzer' && <><div className="mode-selector"><div className="segmented" aria-label="Mode">{(['RTA','Spectrum'] as const).map(mode=><button key={mode} className={draft.mode===mode?'selected':''} aria-pressed={draft.mode===mode} onClick={()=>setDraft({...draft,mode})}>{mode}</button>)}</div></div>
      <div className="settings-body" key={draft.mode}>
        <div className="settings-group"><h2>Band</h2>{field('low','Low frequency','Hz')}{field('high','High frequency','Hz')}</div>
        <div className="settings-group"><h2>{draft.mode}</h2>
        {draft.mode==='RTA' ? <>
          {field('rtaWidth','Window width','s')}{field('rtaHop','Hop','s')}
          <label className="field"><span>RTA detail</span><select aria-label="RTA detail" value={draft.rtaFraction} onChange={e=>setDraft({...draft,rtaFraction:Number(e.target.value)})}>
            {[3,6,12].map(n=><option key={n} value={n}>1/{n} octave</option>)}
            <option value={512}>512 / 0.3 oct</option><option value={1024}>1024 / 0.15 oct</option>
          </select></label>
        </> : <>
          {field('duration','Duration','s')}{field('smoothing','Smoothing','oct')}{field('spectrumPoints','Point count','')}
          <label className="field"><span>Online Welch</span><input aria-label="Online Welch" className="switch" type="checkbox" checked={draft.onlineWelch} onChange={e=>setDraft(updateNumeric({...draft,onlineWelch:e.target.checked},'duration',draft.duration))}/></label>
          {draft.onlineWelch && <>{field('welchSize','Welch window','s')}{field('welchHop','Welch hop','s')}</>}
        </>}</div>
        <div className="settings-group input-group"><h2>Input</h2>
          {devices.length>1 || (draft.inputDeviceId>=0 && !selectedInput) ? <label className="field"><span>Microphone</span><select aria-label="Microphone" value={selectedInputId} disabled={gainBusy} onChange={e=>{const next={...draft,inputDeviceId:Number(e.target.value),referenceDeviceId:devices.find(d=>d.id===Number(e.target.value) && !d.builtIn)?.id??draft.referenceDeviceId};const curve=calibrations.find(c=>c.id===next.calibrationId && c.inputDeviceId===next.inputDeviceId);setDraft(curve?clampToCalibration(next,curve):next);}}>
            {!selectedInput && draft.inputDeviceId>=0 && <option value={draft.inputDeviceId}>Disconnected input</option>}
            {devices.map(d=><option key={d.id} value={d.id}>{d.name}</option>)}
          </select></label> : <div className="field"><span>Microphone</span><span>{selectedInput?.name??'Unavailable'}</span></div>}
          {selectedInput?.gainControl && <InputGainControl key={selectedInput.id} deviceId={selectedInput.id} disabled={busy || state.running} onBusyChange={setGainBusy}/>}
        <button className="settings-link" disabled={gainBusy} onClick={()=>{setSettingsPage('calibration');setError('');}} aria-label="Open calibration settings"><span>Microphone calibration</span><span aria-hidden="true">›</span></button>
        </div>
        {error && <p className="settings-error" role="alert">{error}</p>}
      </div></>}
      {settingsPage==='calibration' && <div className="settings-body calibration-body">
        <div className="calibration-controls">
        <div className="settings-group"><h2>Calibration</h2>
          <label className="field"><span>Use calibration</span><input className="switch" type="checkbox" aria-label="Use calibration" checked={!!draft.calibrationId} disabled={busy || !latestCalibration} onChange={e=>chooseCalibration(e.target.checked?latestCalibration!.id:'')}/></label>
        </div>
        <div className="settings-group calibration-group"><h2>Instructions</h2>
          <p className="calibration-note">Place the reference capsule beside the built-in microphone opening. Keep the microphones and speaker in place for all four recordings. Use a quiet space with minimal reverberation.</p>
          <button className="calibration-button" disabled={busy || gainBusy} onClick={()=>void runCalibration()}>{busy?'Calibrating…':'Measure calibration'}</button>
          {busy && state.calibrationPhase && <p className="calibration-note">{calibrationPhaseText[state.calibrationPhase]??'Preparing calibration'} · {Math.min(30,Math.round(state.elapsed))} / 30 s</p>}
        </div>
        {error && <p className="settings-error" role="alert">{error}</p>}
        </div>
        {latestCalibration ? <div className="calibration-result"><p>Valid range: {Math.round(latestCalibration.fMin)}–{Math.round(latestCalibration.fMax)} Hz</p><CalibrationPlot curve={latestCalibration}/></div> : <p className="calibration-note">No calibration recorded yet.</p>}
      </div>}
    </section>}
  </main>;
}
function CalibrationPlot({curve}:{curve:Calibration}) {
  const width=360,height=210,left=40,right=322,top=20,bottom=178;
  const x=(f:number)=>left+Math.log(f/20)/Math.log(1000)*(right-left);
  const referenceHz=Math.max(curve.fMin,Math.min(1000,curve.fMax));
  const rawSpectra=curve.spectra;
  function atReference(f:number[],db:number[]) {
    const right=f.findIndex(v=>v>=referenceHz);
    if(right<=0)return db[right===0?0:db.length-1];
    const t=Math.log(referenceHz/f[right-1])/Math.log(f[right]/f[right-1]);
    return db[right-1]*(1-t)+db[right]*t;
  }
  const phoneOffset=rawSpectra?atReference(rawSpectra.frequencies,rawSpectra.phoneSignalDb):0;
  const externalOffset=rawSpectra?atReference(rawSpectra.frequencies,rawSpectra.referenceSignalDb):0;
  const displaySpectrum=(values:number[],offset:number)=>values.map((v,i)=>v-offset+10*Math.log10(rawSpectra!.frequencies[i]/referenceHz));
  const spectra=rawSpectra?{...rawSpectra,
    phoneSignalDb:displaySpectrum(rawSpectra.phoneSignalDb,phoneOffset),phoneNoiseDb:displaySpectrum(rawSpectra.phoneNoiseDb,phoneOffset),
    referenceSignalDb:displaySpectrum(rawSpectra.referenceSignalDb,externalOffset),referenceNoiseDb:displaySpectrum(rawSpectra.referenceNoiseDb,externalOffset)}:undefined;
  const all=spectra?[...spectra.phoneNoiseDb,...spectra.referenceNoiseDb,...spectra.phoneSignalDb,...spectra.referenceSignalDb]:[-140,-20];
  const low=Math.floor(Math.min(...all)/20)*20,high=Math.ceil(Math.max(...all)/20)*20;
  const y=(db:number)=>bottom-(db-low)/Math.max(20,high-low)*(bottom-top);
  const correctionY=(db:number)=>bottom-(db+20)/40*(bottom-top);
  const line=(f:number[],v:number[],correction=false)=>f.map((a,i)=>x(a)+','+(correction?correctionY(v[i]):y(v[i]))).join(' ');
  const traces=spectra?[
    {name:'Built-in noise',data:spectra.phoneNoiseDb,color:'#79c5aa',noise:true},
    {name:'External noise',data:spectra.referenceNoiseDb,color:'#e8c78e',noise:true},
    {name:'Built-in signal',data:spectra.phoneSignalDb,color:'#79c5aa',noise:false},
    {name:'External signal',data:spectra.referenceSignalDb,color:'#e8c78e',noise:false},
  ]:[];
  return <><svg className="calibration-plot" viewBox={'0 0 '+width+' '+height} role="img" aria-label="Background noise, signal spectra, correction and valid calibration range">
    <rect x={x(curve.fMin)} y={top} width={x(curve.fMax)-x(curve.fMin)} height={bottom-top} fill="#a6ebce12"/>
    {[0,1,2,3,4].map(i=>{const v=low+(high-low)*i/4;return <g key={i}><path d={'M'+left+' '+y(v)+'H'+right} stroke="#33403a"/><text x={left-5} y={y(v)+3} textAnchor="end">{Math.round(v)}</text><text x={right+5} y={bottom-i/4*(bottom-top)+3}>{-20+i*10}</text></g>;})}
    {[20,100,1000,10000,20000].map(f=><g key={f}><path d={'M'+x(f)+' '+top+'V'+bottom} stroke="#33403a"/><text x={x(f)} y={bottom+15} textAnchor="middle">{f>=1000?f/1000+'k':f}</text></g>)}
    <text x={left} y="11">Relative dB</text><text x={right} y="11" textAnchor="end">Correction dB</text><text x={(left+right)/2} y="207" textAnchor="middle">Hz</text>
    {traces.map(t=><polyline key={t.name} points={line(spectra!.frequencies,t.data)} fill="none" stroke={t.color} strokeWidth="1.3" strokeDasharray={t.noise?'3 3':undefined}/>)}
    {[curve.fMin,curve.fMax].map(f=><path key={f} d={'M'+x(f)+' '+top+'V'+bottom} stroke="#a6ebce" strokeDasharray="2 3"/>)}
    <polyline points={line(curve.frequencies,curve.correctionDb,true)} fill="none" stroke="#c6a4fa" strokeWidth="1.8"/>
  </svg><div className="calibration-legend">{traces.map(t=><span key={t.name} style={{color:t.color}}>{t.noise?'┄':'━'} {t.name}</span>)}<span style={{color:'#c6a4fa'}}>━ Correction</span><span>Shaded: valid range</span></div>{spectra?<p className="calibration-note">Signals aligned at {Math.round(referenceHz)} Hz; noise uses the same offsets.</p>:<p className="calibration-note">Record a new calibration to display its noise and signal spectra.</p>}</>;
}
function InputLevelMeter({dbfs}:{dbfs:number}) {
  const value=Math.max(-80,Math.min(0,Number.isFinite(dbfs)?dbfs:-80));
  return <div className="input-level" role="meter" aria-label="Input peak level" aria-valuemin={-80} aria-valuemax={0} aria-valuenow={Math.round(value)}>
    <div className="input-level-track"><div className="input-level-fill" style={{'--level':`${(value+80)/80*100}%`} as React.CSSProperties}/></div>
  </div>;
}
function InputGainControl({deviceId,disabled,onBusyChange}:{deviceId:number;disabled:boolean;onBusyChange:(busy:boolean)=>void}) {
  const [gain,setGain]=useState<{gainDb:number;minDb:number;maxDb:number}|null>(null);
  const [draft,setDraft]=useState(0);
  const [working,setWorking]=useState(false);
  const [error,setError]=useState('');
  const applying=useRef(false);
  function commit(value:number) {if(!disabled && !applying.current && gain && value!==gain.gainDb) void read(value);}
  async function read(requested?:number) {
    if(applying.current)return;applying.current=true;
    setWorking(true);onBusyChange(true);setError('');
    try {
      const result=await Spectrum.inputGain({deviceId,...(requested===undefined?{}:{gainDb:requested})});
      setGain(result);setDraft(result.gainDb);
    } catch(e) {setError(e instanceof Error?e.message:String(e));}
    finally {applying.current=false;setWorking(false);onBusyChange(false);}
  }
  useEffect(()=>{
    if(disabled || gain || working) return;
    void read();
  },[deviceId,disabled]);
  return <div className="reference-gain">
    {gain ? <><label className="slider-field"><span className="slider-heading"><span>Input gain</span><span className="slider-value">{draft} <small>dB</small></span></span><input aria-label="Input gain" type="range" min={gain.minDb} max={gain.maxDb} step="1" value={draft} disabled={disabled || working} onChange={e=>setDraft(Number(e.target.value))} onPointerUp={e=>commit(Number(e.currentTarget.value))} onKeyUp={e=>commit(Number(e.currentTarget.value))} onBlur={e=>commit(Number(e.currentTarget.value))}/></label>
      </>
      : <span className="calibration-note">{working?'Reading input gain…':'Input gain unavailable'}</span>}
    {error && <p className="settings-error" role="alert">{error} <button disabled={disabled || working} onClick={()=>void read()}>Retry</button></p>}
  </div>;
}
