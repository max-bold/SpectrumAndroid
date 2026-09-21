import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
export const adb = process.env.ADB || 'C:/platform-tools/adb.exe';
export async function connect() {
  const pid = execFileSync(adb,['shell','pidof','com.bm.spectrum'],{encoding:'utf8'}).trim();
  if(!pid) throw new Error('Start BM Spectrum on the phone first');
  execFileSync(adb,['forward','tcp:9223',`localabstract:webview_devtools_remote_${pid}`]);
  const targets = await (await fetch('http://127.0.0.1:9223/json', { signal: AbortSignal.timeout(10000) })).json();
  const target = targets.find(t=>t.url.startsWith('https://localhost'));
  if(!target) throw new Error('BM Spectrum WebView not found');
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{ws.close();reject(new Error('WebView connection timed out'));},10000);
    ws.onopen=()=>{clearTimeout(timer);resolve();};ws.onerror=e=>{clearTimeout(timer);reject(e);};
  });
  let id=0;
  const pending=new Map();
  ws.onmessage=event=>{
    const m=JSON.parse(event.data);
    const p=pending.get(m.id);
    if(p) {pending.delete(m.id); clearTimeout(p.timer); m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result);}
  };
  function send(method,params={}) {
    return new Promise((resolve,reject)=>{
      const request=++id;
      const timer=setTimeout(()=>{pending.delete(request);reject(new Error(`Timed out: ${method}`));},30000);
      pending.set(request,{resolve,reject,timer}); ws.send(JSON.stringify({id:request,method,params}));
    });
  }
  return {
    send,
    async evaluate(expression) {
      const r=await send('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});
      if(r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails));
      return r.result.value;
    },
    screenshot(name) {
      mkdirSync('test-results',{recursive:true});
      writeFileSync(`test-results/${name}.png`,execFileSync(adb,['exec-out','screencap','-p'],{maxBuffer:20*1024*1024}));
    },
    close() { ws.close(); execFileSync(adb,['forward','--remove','tcp:9223']); }
  };
}
