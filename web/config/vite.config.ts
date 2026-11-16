import { defineConfig } from 'vite';

// Les fichiers déposés dans public/ (iptv-home.apk, version.json) sont servis tels quels.
export default defineConfig({
  server: { host: '0.0.0.0', allowedHosts: ['terminal.local'] },
  test: { environment: 'node' },
} as never);
