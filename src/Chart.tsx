import { useEffect, useRef, useState } from 'react';
import type { PlotData, Settings } from './model';

const XMIN=20, XMAX=20000;
const LEFT=56, TOP=12, RIGHT=14, BOTTOM=42;
const frequencyText=(f:number)=>f>=1000 ? `${Number((f/1000).toPrecision(4))} kHz` : `${Number(f.toPrecision(4))} Hz`;
export default function Chart({ data, settings, running, elapsed }: { data: PlotData|null; settings:Settings; running:boolean; elapsed:number }) {
  const canvas=useRef<HTMLCanvasElement>(null);
  const [size,setSize]=useState({width:300,height:600});
  const [range,setRange]=useState({top:10,span:100});
  const [cursor,setCursor]=useState<number|null>(null);
  const pointers=useRef(new Map<number,{x:number;y:number}>());
  const last=useRef<{y:number;distance:number}|null>(null);
  const drag=useRef<{x:number;y:number;mode:'pending'|'cursor'|'pan'|'pinch'}|null>(null);
  const [progress,setProgress]=useState(0);
  const progressClock=useRef({elapsed:0,at:0});
  useEffect(()=>{
    const observer=new ResizeObserver(([e])=>setSize({width:e.contentRect.width,height:e.contentRect.height}));
    observer.observe(canvas.current!);return ()=>observer.disconnect();
  },[]);
  useEffect(()=>{progressClock.current={elapsed,at:performance.now()};},[elapsed,running]);
  useEffect(()=>{
    if(!running || settings.mode!=='Spectrum') {setProgress(0);return;}
    let handle=0;
    const update=()=>{setProgress(Math.min(1,(progressClock.current.elapsed+(performance.now()-progressClock.current.at)/1000)/settings.duration));handle=requestAnimationFrame(update);};
    handle=requestAnimationFrame(update);return ()=>cancelAnimationFrame(handle);
  },[running,settings.mode,settings.duration]);
  useEffect(()=>{
    const node=canvas.current!,ctx=node.getContext('2d')!,dpr=window.devicePixelRatio||1;
    const {width:w,height:h}=size;
    node.width=Math.round(w*dpr);node.height=Math.round(h*dpr);ctx.scale(dpr,dpr);
    const left=LEFT,right=w-RIGHT,top=TOP,bottom=h-BOTTOM;
    if(right<=left || bottom<=top) return;
    const x=(f:number)=>left+Math.log(f/XMIN)/Math.log(XMAX/XMIN)*(right-left);
    const y=(db:number)=>top+(range.top-db)/range.span*(bottom-top);
    ctx.font='11px ui-monospace, monospace';ctx.lineWidth=1;
    const restricted=settings.low!==20 || settings.high!==20000;
    if(restricted) {
      ctx.fillStyle='#a6ebce09';ctx.fillRect(x(settings.low),top,x(settings.high)-x(settings.low),bottom-top);
      ctx.fillStyle='#00000032';ctx.fillRect(left,top,x(settings.low)-left,bottom-top);ctx.fillRect(x(settings.high),top,right-x(settings.high),bottom-top);
    }
    for(const f of [20,30,40,50,60,80,100,200,300,400,500,600,800,1000,2000,3000,4000,5000,6000,8000,10000,20000]) {
      ctx.strokeStyle=[20,100,1000,10000,20000].includes(f)?'#343a3d':'#202629';
      ctx.beginPath();ctx.moveTo(x(f),top);ctx.lineTo(x(f),bottom);ctx.stroke();
    }
    const yStep=range.span>120?20:range.span>50?10:range.span>25?5:2;
    for(let db=Math.ceil((range.top-range.span)/yStep)*yStep;db<=range.top;db+=yStep) {
      ctx.strokeStyle='#272e31';ctx.beginPath();ctx.moveTo(left,y(db));ctx.lineTo(right,y(db));ctx.stroke();
      ctx.fillStyle='#879196';ctx.textAlign='right';ctx.fillText(String(db),left-7,y(db)+4);
    }
    ctx.fillStyle='#9ba8ac';ctx.textAlign='center';
    for(const f of [20,50,100,200,500,1000,2000,5000,10000,20000]) {
      if(w<500 && [50,200,2000,10000].includes(f)) continue;
      ctx.textAlign=f===20000?'right':f===20?'left':'center';
      ctx.fillText(f>=1000?`${f/1000}k`:`${f}`,x(f),bottom+18);
    }
    ctx.textAlign='center';ctx.fillText('Hz',(left+right)/2,h-5);
    ctx.save();ctx.translate(13,(top+bottom)/2);ctx.rotate(-Math.PI/2);ctx.fillText(`${settings.mode} dB`,0,0);ctx.restore();
    ctx.save();ctx.beginPath();ctx.rect(left,top,right-left,bottom-top);ctx.clip();
    if(restricted) {
      ctx.strokeStyle='#a6ebce60';ctx.setLineDash([3,4]);
      for(const f of [settings.low,settings.high]) {ctx.beginPath();ctx.moveTo(x(f),top);ctx.lineTo(x(f),bottom);ctx.stroke();}
      ctx.setLineDash([]);
    }
    if(data?.frequency.length) {
      const f=data.frequency;
      if(settings.mode==='RTA') {
        const ratio=10**(0.3/(2*settings.rtaFraction));
        for(let i=0;i<f.length;i++) {
          const a=x(Math.max(settings.low,f[i]/ratio)),b=x(Math.min(settings.high,f[i]*ratio));
          const yy=Math.max(top,Math.min(bottom,y(data.db[i])));
          ctx.fillStyle='#81d3b790';ctx.fillRect(a+0.6,yy,Math.max(0,b-a-1.2),bottom-yy);
          ctx.fillStyle='#a6ebce';ctx.fillRect(a+0.6,yy,Math.max(0,b-a-1.2),1.5);
        }
      } else {
        ctx.strokeStyle='#a6ebce';ctx.lineWidth=1.6;ctx.beginPath();
        f.forEach((v,i)=>{if(i===0)ctx.moveTo(x(v),y(data.db[i]));else ctx.lineTo(x(v),y(data.db[i]));});ctx.stroke();
      }
    }
    if(running && settings.mode==='Spectrum') {
      const start=x(settings.low),end=x(settings.high),head=start+(end-start)*progress;
      ctx.fillStyle='#a6ebce25';ctx.fillRect(start,bottom-4,end-start,4);
      ctx.fillStyle='#a6ebce';ctx.fillRect(start,bottom-4,head-start,4);
    }
    ctx.restore();
    if(cursor!==null) {
      let frequency=cursor,db:number|undefined;
      if(data?.frequency.length) {
        let best=0;
        for(let i=1;i<data.frequency.length;i++) if(Math.abs(Math.log(data.frequency[i]/cursor))<Math.abs(Math.log(data.frequency[best]/cursor)))best=i;
        frequency=data.frequency[best];db=data.db[best];
      }
      const xx=Math.max(left,Math.min(right,x(frequency)));
      ctx.strokeStyle='#e8c78e';ctx.lineWidth=1;ctx.setLineDash([4,4]);ctx.beginPath();ctx.moveTo(xx,top);ctx.lineTo(xx,bottom);ctx.stroke();ctx.setLineDash([]);
      if(db!==undefined) {ctx.fillStyle='#e8c78e';ctx.beginPath();ctx.arc(xx,Math.max(top,Math.min(bottom,y(db))),3,0,2*Math.PI);ctx.fill();}
      const label=`${frequencyText(frequency)}  ·  ${db===undefined?'—':db.toFixed(1)+' dB'}`;
      const labelWidth=ctx.measureText(label).width+16;
      const labelX=Math.max(left+2,Math.min(right-labelWidth-2,xx-labelWidth/2));
      const labelY=bottom-32;
      ctx.fillStyle='#101416ed';ctx.fillRect(labelX,labelY,labelWidth,23);
      ctx.fillStyle='#e8c78e';ctx.textAlign='left';ctx.fillText(label,labelX+8,labelY+15);
      node.setAttribute('data-cursor-frequency',String(frequency));node.setAttribute('data-cursor-db',db===undefined?'':String(db));
    } else {node.removeAttribute('data-cursor-frequency');node.removeAttribute('data-cursor-db');}
  },[size,range,data,settings,cursor,running,progress]);
  const pick=(clientX:number)=>{
    const local=clientX-canvas.current!.getBoundingClientRect().left;
    const fraction=Math.max(0,Math.min(1,(local-LEFT)/(size.width-LEFT-RIGHT)));
    setCursor(XMIN*(XMAX/XMIN)**fraction);
  };
  const gesture=()=>{
    const p=[...pointers.current.values()];
    return {y:p.reduce((sum,v)=>sum+v.y,0)/p.length,distance:p.length>1?Math.hypot(p[0].x-p[1].x,p[0].y-p[1].y):0};
  };
  return <canvas ref={canvas} aria-label={`${settings.mode} chart. Tap or drag horizontally for cursor; drag vertically to pan; pinch to zoom.`}
    onPointerDown={e=>{
      e.currentTarget.setPointerCapture(e.pointerId);pointers.current.set(e.pointerId,{x:e.clientX,y:e.clientY});last.current=gesture();
      if(pointers.current.size===1) drag.current={x:e.clientX,y:e.clientY,mode:'pending'};
      else if(drag.current) drag.current.mode='pinch';
    }}
    onPointerMove={e=>{
      if(!pointers.current.has(e.pointerId)||!drag.current)return;
      pointers.current.set(e.pointerId,{x:e.clientX,y:e.clientY});const next=gesture(),prev=last.current,d=drag.current;
      if(d.mode==='pending' && Math.hypot(e.clientX-d.x,e.clientY-d.y)>5) d.mode=Math.abs(e.clientX-d.x)>Math.abs(e.clientY-d.y)?'cursor':'pan';
      if(d.mode==='cursor') pick(e.clientX);
      if(prev && (d.mode==='pan'||(d.mode==='pinch'&&pointers.current.size>1))) {
        const canvasTop=e.currentTarget.getBoundingClientRect().top;
        setRange(r=>{
          const height=Math.max(1,size.height-TOP-BOTTOM),pan=(next.y-prev.y)/height*r.span;
          const span=prev.distance>8 && next.distance>8?Math.max(12,Math.min(180,r.span*prev.distance/next.distance)):r.span;
          const fraction=(next.y-canvasTop-TOP)/height;
          return {top:Math.max(-180,Math.min(100,r.top+pan+(span-r.span)*fraction)),span};
        });
      }
      last.current=next;
    }}
    onPointerUp={e=>{if(drag.current?.mode==='pending')pick(e.clientX);pointers.current.delete(e.pointerId);last.current=pointers.current.size?gesture():null;if(!pointers.current.size)drag.current=null;}}
    onPointerCancel={()=>{pointers.current.clear();last.current=null;drag.current=null;}}
    onDoubleClick={()=>{setRange({top:10,span:100});setCursor(null);}}
    onWheel={e=>setRange(r=>({top:r.top,span:Math.max(12,Math.min(180,r.span*Math.exp(e.deltaY*0.001)))}))}
  />;
}
