import type { Calibration, InputDevice, Settings } from './model';

export function applicableCalibration(s:Settings, curves:Calibration[], devices:InputDevice[], defaultInputId:number) {
  const input=devices.find(d=>d.id===(s.inputDeviceId<0?defaultInputId:s.inputDeviceId));
  // Match AudioEngine's 48 kHz / UNPROCESSED built-in capture configuration.
  return input?.builtIn?curves.find(c=>c.id===s.calibrationId && c.inputDeviceId===input.id && c.sampleRate===48000 && c.audioSource===9):undefined;
}

export function sliderSpec(key: keyof Settings, s: Settings, calibration?:Calibration) {
  const specs: Partial<Record<keyof Settings,{min:number;max:number;step:number;log?:boolean;power?:boolean}>> = {
    low:{min:20,max:20000,step:1,log:true},high:{min:20,max:20000,step:1,log:true},
    duration:{min:0.5,max:30,step:0.1},smoothing:{min:0.1,max:1,step:0.01},
    spectrumPoints:{min:32,max:1024,step:1},welchSize:{min:10,max:18,step:1,power:true},
    welchHop:{min:1,max:s.welchSize,step:1},rtaWidth:{min:0.1,max:10,step:0.1},
    rtaHop:{min:0.02,max:s.rtaWidth,step:0.01},
  };
  const spec=specs[key]!;
  if(calibration && (key==='low' || key==='high')) return {...spec,min:calibration.fMin,max:calibration.fMax};
  return spec;
}
export function updateNumeric(s:Settings,key:keyof Settings,value:number,calibration?:Calibration):Settings {
  const next={...s,[key]:Number(value.toFixed(4))};
  if(calibration && (key==='low' || key==='high')) next[key]=value;
  if(key==='low') next.low=Math.min(next.low,next.high-1);
  if(key==='high') next.high=Math.max(next.high,next.low+1);
  if(calibration && (key==='low' || key==='high')) {
    const gap=Math.min(1,(calibration.fMax-calibration.fMin)/2);
    next.low=Math.max(calibration.fMin,Math.min(next.low,calibration.fMax-gap));
    next.high=Math.min(calibration.fMax,Math.max(next.high,next.low+gap));
  }
  if(key==='rtaWidth') next.rtaHop=Math.min(next.rtaHop,next.rtaWidth);
  if(key==='welchSize') {
    next.welchHop=Math.max(1,Math.round(next.welchSize*s.welchHop/s.welchSize));
    if(next.onlineWelch) next.duration=Math.max(next.duration,Math.ceil(next.welchSize/48000*10)/10);
  }
  if(key==='duration' && next.onlineWelch && next.welchSize>next.duration*48000) {
    const ratio=next.welchHop/next.welchSize;
    next.welchSize=2**Math.floor(Math.log2(next.duration*48000));
    next.welchHop=Math.max(1,Math.round(next.welchSize*ratio));
  }
  return next;
}
