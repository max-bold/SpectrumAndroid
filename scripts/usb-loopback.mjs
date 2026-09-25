// Requires a USB interface with Line Out connected to Line In and the app foreground.
// Emits the normal 0.9-peak generators. Leaves saved settings and media volume unchanged.
import {connect, adb} from './webview.mjs';
import {execFileSync} from 'node:child_process';
import {mkdirSync, writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';

const c = await connect();
const delay = ms => new Promise(r => setTimeout(r, ms));
const report = {};
const state = () => c.evaluate('Capacitor.Plugins.Spectrum.getState()');
const median = values => [...values].sort((a,b) => a-b)[Math.floor(values.length/2)];
const level = p => median(p.db.filter((_,i) => p.frequency[i] >= 200 && p.frequency[i] <= 10000));
async function measure(name, settings, milliseconds) {
  await c.evaluate('window.__usbPlots=[]');
  const started = Date.now();
  await c.evaluate(`Capacitor.Plugins.Spectrum.start({settings:${JSON.stringify(settings)}})`);
  await delay(milliseconds);
  const active = await state();
  await c.evaluate('Capacitor.Plugins.Spectrum.stop()');
  const stopped = await state();
  const plots = await c.evaluate('window.__usbPlots');
  assert.equal(stopped.error, '');
  assert.equal(stopped.running, false);
  assert.equal(stopped.generating, false);
  assert.equal(stopped.keepScreenOn, false);
  assert.ok(plots.length > 0, `${name}: no plots`);
  const last = plots.at(-1);
  assert.ok(last.db.every(Number.isFinite));
  report[name] = {active, stopped, wallMs: Date.now()-started, levelDb: level(last), last};
  console.log(name, {levelDb: level(last), seconds: stopped.elapsed, kind: last.kind});
  return report[name];
}
let listener;
try {
  await c.evaluate('Capacitor.Plugins.Spectrum.stop()');
  listener = await c.evaluate(`(async()=>{window.__usbPlots=[];const h=await Capacitor.Plugins.Spectrum.addListener('plot',p=>window.__usbPlots.push(p));window.__usbListener=h;return true})()`);
  const base = {mode:'RTA', rtaWidth:0.5, rtaHop:0.1, generatorEnabled:false};
  const quiet = await measure('generatorOff', base, 2000);
  for (let i=0; i<2; i++) {
    const rta = await measure(`rta${i}`, {...base, generatorEnabled:true}, 4000);
    assert.ok(rta.levelDb > quiet.levelDb + 30, 'USB loopback must return the generated signal');
    assert.ok(rta.active.elapsed > 3.5 && rta.active.elapsed < 5, 'Capture must run in real time');
  }
  const sweep = await measure('spectrum', {mode:'Spectrum',duration:3,generatorEnabled:true}, 5500);
  assert.equal(sweep.active.running, false, 'Sweep must finish automatically');
  assert.equal(sweep.last.kind, 'periodogram');
  assert.equal(sweep.last.fftSize, 192000);
  assert.ok(sweep.levelDb > quiet.levelDb + 30);
  const early = await measure('earlyStop', {mode:'Spectrum',duration:5,generatorEnabled:true}, 1300);
  assert.equal(early.last.kind, 'periodogram');
  assert.ok(early.last.fftSize < 288000);
  const after = await measure('generatorOffAfter', base, 2000);
  assert.ok(after.levelDb < report.rta1.levelDb - 30, 'Playback must stop');
  assert.ok(after.active.elapsed > 1.7, 'Capture must recover after playback');
} finally {
  try {
    await c.evaluate('Capacitor.Plugins.Spectrum.stop()');
    if (listener) await c.evaluate('window.__usbListener.remove()');
    mkdirSync('test-results', {recursive:true});
    writeFileSync('test-results/usb-loopback.json', JSON.stringify(report,null,2));
    writeFileSync('test-results/usb-loopback-policy.txt', execFileSync(adb,['shell','dumpsys','media.audio_policy']));
  } finally { c.close(); }
}
