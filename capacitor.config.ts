import type { CapacitorConfig } from '@capacitor/cli';
const config: CapacitorConfig = {
  appId: 'com.bm.spectrum', appName: 'BM Spectrum', webDir: 'dist',
  android: { backgroundColor: '#101416' },
  plugins: { SystemBars: { style: 'DARK', initialViewportFitValueHint: 'cover' } }
};
export default config;
