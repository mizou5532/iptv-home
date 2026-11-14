import { defineConfig } from 'vite';

// Les fichiers déposés dans public/ (iptv-home.apk, version.json) sont servis tels quels.
export default defineConfig({
  test: { environment: 'node' },
} as never);
