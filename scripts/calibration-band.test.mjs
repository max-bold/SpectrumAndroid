import test from 'node:test';
import assert from 'node:assert/strict';
import { defaults, clampToCalibration } from '../src/model.ts';
import { applicableCalibration, sliderSpec, updateNumeric } from '../src/settings-controls.ts';

const curve={id:'phone',inputDeviceId:7,sampleRate:48000,audioSource:9,fMin:59.37,fMax:17003.24};
const devices=[{id:7,builtIn:true},{id:8,builtIn:false}];
const settings={...defaults,inputDeviceId:7,calibrationId:'phone'};

test('band limits apply only to an enabled matching built-in calibration, including automatic input',()=>{
  assert.equal(applicableCalibration(settings,[curve],devices,8),curve);
  assert.equal(applicableCalibration({...settings,inputDeviceId:-1},[curve],devices,7),curve);
  for(const s of [{...settings,calibrationId:''},{...settings,inputDeviceId:8},{...settings,inputDeviceId:99},{...settings,inputDeviceId:-1}])
    assert.equal(applicableCalibration(s,[curve],devices,8),undefined);
  for(const mismatch of [{inputDeviceId:9},{sampleRate:44100},{audioSource:1}])
    assert.equal(applicableCalibration(settings,[{...curve,...mismatch}],devices,7),undefined);
  for(const key of ['low','high']) {
    assert.equal(sliderSpec(key,settings,curve).min,curve.fMin);
    assert.equal(sliderSpec(key,settings,curve).max,curve.fMax);
    assert.equal(sliderSpec(key,settings).min,20);
    assert.equal(sliderSpec(key,settings).max,20000);
  }
});

test('every log slider position and rounded endpoint stays inside calibration without crossing bands',()=>{
  for(const mode of ['RTA','Spectrum']) {
    const s=clampToCalibration({...settings,mode},curve);
    for(const key of ['low','high']) for(let position=0;position<=1000;position++) {
      const value=curve.fMin*(curve.fMax/curve.fMin)**(position/1000);
      const rounded=value>=1000?Math.round(value/10)*10:Math.round(value);
      const next=updateNumeric(s,key,rounded,curve);
      assert.ok(next.low>=curve.fMin && next.high<=curve.fMax && next.low<next.high);
    }
    assert.equal(updateNumeric(s,'low',Math.round(curve.fMin),curve).low,curve.fMin);
    assert.equal(updateNumeric(s,'high',20000,curve).high,curve.fMax);
    assert.equal(updateNumeric(s,'high',curve.fMax,curve).high,curve.fMax);
  }
});
