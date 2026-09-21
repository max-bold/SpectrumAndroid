"""Generate portable regression vectors from the existing BM Spectrum source.
Run with the desktop project's Python environment. Python is never packaged.
"""
import importlib.util
import json
from pathlib import Path
import sys

import numpy as np
from scipy.signal import chirp, periodogram, welch

source = Path(sys.argv[1] if len(sys.argv) > 1 else "../Spectrum")
spec = importlib.util.spec_from_file_location("bm_smoothing", source / "audioanalysis/smoothing.py")
smoothing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoothing)
rate, low, high, points, width = 8000, 20, 3500, 64, 0.3

def smooth(f, p):
    mask = (f >= low) & (f <= high)
    _, result = smoothing.log_smooth(f[mask], (p*f)[mask], band=(low, high), points=points, width=width)
    return np.nan_to_num(result).tolist()

cases = []
for n, hann in [(4096, True), (4095, True), (4096, False)]:
    t = np.arange(n)/rate
    x = 0.37*np.sin(2*np.pi*337*t) + 0.19*np.cos(2*np.pi*1721.2*t) + 0.07 + 0.03*np.sin(t*t*800)
    f, p = periodogram(x, rate, window="hann" if hann else "boxcar")
    cases.append(dict(n=n, hann=hann, signal=x.tolist(), expected=smooth(f, p)))

n = 12000
t = np.arange(n)/rate
x = (0.1+0.3*t)*np.sin(2*np.pi*400*t) + 0.05*np.cos(2*np.pi*900*t)
f, p = welch(x.astype(np.float32).astype(np.float64), rate, window="hann", nperseg=2048, noverlap=1536)

def pink(n):
    f = np.fft.rfftfreq(n, 1/rate)
    phases = np.mod(np.arange(len(f))*1.618033988749895, 2*np.pi)
    edge = (10**0.05-1)**(1/8)
    a = np.zeros_like(f)
    a[1:] = 1/np.sqrt(f[1:])/np.sqrt(1+(low*edge/f[1:])**8)/np.sqrt(1+(f[1:]/(high/edge))**8)
    sp = a*np.exp(1j*phases)
    if n % 2 == 0:
        sp[-1] = a[-1]*(1 if phases[-1] < np.pi else -1)
    y = np.fft.irfft(sp, n=n)
    y = (y/np.max(np.abs(y))*0.5).astype(np.float32)
    return dict(n=n, phases=phases.tolist(), expected=y.tolist())

c = chirp(np.arange(1024)/rate, low, 1024/rate, high, method="logarithmic", phi=-90)
c = (c/np.max(np.abs(c))*0.5).astype(np.float32)
out = dict(rate=rate, low=low, high=high, points=points, width=width, cases=cases,
           welch=dict(signal=x.astype(np.float32).tolist(), expected=smooth(f,p)),
           pink=[pink(1024),pink(1023)], chirp=c.tolist())
dest = Path(__file__).resolve().parents[1]/"android/app/src/test/resources/reference.json"
dest.parent.mkdir(parents=True, exist_ok=True)
dest.write_text(json.dumps(out, separators=(",",":")), encoding="utf-8")
print(f"Wrote {dest} ({dest.stat().st_size} bytes)")
