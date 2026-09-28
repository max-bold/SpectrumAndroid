import test from 'node:test';
import assert from 'node:assert/strict';
import { parseCalibrations, clampToCalibration, defaults } from '../src/model.ts';

test('calibration survives JSON storage and restricts the selected band', () => {
  const curve = {
    id:'phone-1', inputDeviceId:7, sampleRate:48000, audioSource:9,
    fMin:55, fMax:16000, averageRepeatability:0.97, phoneSnrDb:30, referenceSnrDb:35,
    frequencies:Array.from({length:128},(_,i)=>i===127?16000:55*(16000/55)**(i/127)),
    correctionDb:Array.from({length:128},(_,i)=>-4+8*i/127),
    responseDb:Array.from({length:128},(_,i)=>4-8*i/127),
  };
  const restored=parseCalibrations(JSON.stringify([curve]));
  assert.deepEqual(restored,[curve]);
  const latest={...curve,id:'phone-2'};
  assert.deepEqual(parseCalibrations(JSON.stringify([curve,latest])),[latest]);
  assert.deepEqual(parseCalibrations(JSON.stringify([{...curve,spectra:{}}])),[]);
  assert.deepEqual([clampToCalibration(defaults,restored[0]).low,clampToCalibration(defaults,restored[0]).high],[55,16000]);
  assert.deepEqual(parseCalibrations(JSON.stringify([{...curve,correctionDb:curve.correctionDb.map(()=>20)}])).length,1);
  assert.deepEqual(parseCalibrations(JSON.stringify([{...curve,correctionDb:curve.correctionDb.map(()=>20.01)}])),[]);
  assert.deepEqual(parseCalibrations(JSON.stringify([null,curve,{...curve,responseDb:'bad'}])),[curve]);
  assert.deepEqual(parseCalibrations('bad json'),[]);
});
