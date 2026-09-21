// Real-device regression checks; microphone permission must already be granted.
// Briefly emits pink noise and chirp at the app's fixed 0.9 digital peak.
import {connect,adb} from './webview.mjs';
import {execFileSync} from 'node:child_process';
import {writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const c=await connect(), delay=ms=>new Promise(r=>setTimeout(r,ms));
const defaults={mode:'RTA',low:20,high:20000,duration:5,smoothing:0.3,spectrumPoints:256,onlineWelch:true,welchSize:8192,welchHop:4096,rtaWidth:3,rtaHop:.1,rtaFraction:3,generatorEnabled:false};
const original=await c.evaluate(`localStorage.getItem('bm-settings-v2')`);
const report={};
const click=async label=>{await c.evaluate(`document.querySelector(${JSON.stringify(`[aria-label="${label}"]`)}).click()`);await delay(150);await until('!document.querySelector("button[aria-busy=true]")');};
const state=()=>c.evaluate('Capacitor.Plugins.Spectrum.getState()');
async function until(expression,timeout=15000){const start=Date.now();while(Date.now()-start<timeout){if(await c.evaluate(expression))return;await delay(100);}throw Error(expression);}
async function configure(settings){
  await c.evaluate('Capacitor.Plugins.Spectrum.stop()');
  await c.evaluate(`localStorage.setItem('bm-settings-v2',${JSON.stringify(JSON.stringify({...defaults,...settings}))});location.reload()`);
  await delay(500);
  await until('!!window.Capacitor?.Plugins?.Spectrum');
  await c.evaluate(`(async()=>{window.__bm={plots:[],errors:[]};window.addEventListener('error',e=>__bm.errors.push(e.message));window.addEventListener('unhandledrejection',e=>__bm.errors.push(String(e.reason)));await Capacitor.Plugins.Spectrum.addListener('plot',p=>__bm.plots.push(p));return true})()`);
}

try {
  await configure({});
  await click('Use generator');
  assert.equal((await state()).generating,false,'Arming must not play audio');
  assert.equal((await state()).running,false);
  await click('Start measurement');
  await until('__bm.plots.length>=4');
  assert.equal((await state()).generating,true);
  assert.equal((await state()).keepScreenOn,true);
  const rta=await c.evaluate('__bm.plots.at(-1)');
  assert.equal(rta.frequency.length,31);assert.equal(rta.db.every(Number.isFinite),true);
  report.rta={points:rta.db.length,first:rta.frequency[0],last:rta.frequency.at(-1),ms:rta.analysisMs};
  await click('Stop measurement');assert.equal((await state()).generating,false);await delay(1000);assert.equal((await state()).keepScreenOn,false);
  report.generatorFollowsMeasurement=true;report.keepScreenOnlyDuringMeasurement=true;
  const builds=(await state()).cacheBuilds;
  await c.evaluate('__bm.plots=[]');await click('Start measurement');await until('__bm.plots.length>=1');await click('Stop measurement');assert.equal((await state()).cacheBuilds,builds);report.cacheReuse=true;
  await c.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:240,y:330,id:1}]});
  await c.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await delay(150);
  const cursor=await c.evaluate(`({...document.querySelector('canvas').dataset})`);
  assert.ok(Number(cursor.cursorFrequency)>20);assert.ok(Number.isFinite(Number(cursor.cursorDb)));report.cursor=cursor;
  const before=await c.evaluate('document.querySelector("canvas").toDataURL()');
  await c.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:240,y:330,id:1}]});
  await c.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:240,y:420,id:1}]});
  await c.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await delay(100);
  assert.notEqual(await c.evaluate('document.querySelector("canvas").toDataURL()'),before);report.yPan=true;
  c.screenshot('v2-rta-cursor');console.log('RTA, generator arming, screen flag, cache and cursor passed');
  for(const fraction of [6,12]){
    await configure({rtaFraction:fraction,rtaWidth:.2,rtaHop:.05});await click('Start measurement');await until('__bm.plots.length>=2');await click('Stop measurement');
    const f=await c.evaluate('__bm.plots.at(-1).frequency');assert.ok(f.length>31);assert.equal(f.some(v=>Math.abs(v-1000)<1e-8),false);report[`bands${fraction}`]=f.length;
  }
  await configure({mode:'Spectrum',low:100,high:2000,duration:1.5,generatorEnabled:true});
  await click('Start measurement');await delay(300);assert.equal((await state()).generating,true);c.screenshot('v2-sweep-progress');
  await until('__bm.plots.length>0 && !document.querySelector("button.primary.active")');
  assert.equal((await state()).generating,false);assert.equal((await state()).running,false);
  report.welch=await c.evaluate('({points:__bm.plots.at(-1).db.length,kind:__bm.plots.at(-1).kind,fftSize:__bm.plots.at(-1).fftSize,preview:__bm.plots.some(p=>p.kind==="welch")})');
  assert.equal(report.welch.kind,'periodogram');assert.equal(report.welch.fftSize,120000);assert.equal(report.welch.preview,true);
  c.screenshot('v2-spectrum');
  await configure({mode:'Spectrum',duration:1,onlineWelch:false,generatorEnabled:true});await click('Start measurement');
  await until('__bm.plots.length>0 && !document.querySelector("button.primary.active")');await delay(200);assert.equal((await state()).running,false);assert.equal((await state()).generating,false);report.offline=true;
  await click('Settings');assert.equal(await c.evaluate('document.querySelectorAll("input[type=number]").length'),0);assert.ok(await c.evaluate('document.querySelectorAll("input[type=range]").length>=5'));assert.equal(await c.evaluate('document.querySelectorAll("select").length'),0);c.screenshot('v2-settings');await click('Back');report.sliders=true;
  await configure({mode:'Spectrum',duration:5,welchSize:65536,onlineWelch:true});
  await click('Start measurement');await delay(250);await click('Stop measurement');
  const partial=await c.evaluate('__bm.plots.at(-1)');assert.equal(partial.kind,'periodogram');assert.ok(partial.fftSize<65536);report.earlyStopFft=partial.fftSize;
  await click('Settings');
  assert.ok((await c.evaluate('document.querySelectorAll(".slider-field input")[5]?.getAttribute("aria-valuetext")')).endsWith(' s'));
  const oldRotation=execFileSync(adb,['shell','wm','user-rotation'],{encoding:'utf8'}).trim();
  try {
    for(const rotation of ['1','3','0']) {
      execFileSync(adb,['shell','wm','user-rotation','lock',rotation]);await delay(700);
      await c.evaluate('document.querySelector(".settings-body").scrollTop=10000');await delay(100);
      const rect=await c.evaluate('(()=>{const r=document.querySelector(".mode-selector").getBoundingClientRect();return {top:r.top,bottom:r.bottom,height:innerHeight}})()');
      assert.ok(rect.top>=0 && rect.bottom<rect.height);c.screenshot('v02-settings-rotation-'+rotation);
    }
  } finally {execFileSync(adb,['shell','wm','user-rotation',...oldRotation.split(/\s+/)]);}
  report.settingsScroll=true;await click('Back');
  await configure({generatorEnabled:true});await click('Start measurement');await delay(300);
  execFileSync(adb,['shell','input','keyevent','KEYCODE_HOME']);await delay(600);
  execFileSync(adb,['shell','am','start','-n','com.bm.spectrum/.MainActivity']);await delay(500);
  assert.equal((await state()).running,false);assert.equal((await state()).generating,false);report.backgroundStop=true;
  assert.deepEqual(await c.evaluate('__bm.errors'),[]);
  writeFileSync('test-results/device-smoke-v02.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
} finally {
  try{await c.evaluate('Capacitor.Plugins.Spectrum.stop()');await c.evaluate(original===null?`localStorage.removeItem('bm-settings-v2');location.reload()`:`localStorage.setItem('bm-settings-v2',${JSON.stringify(original)});location.reload()`);}finally{c.close();}
}

