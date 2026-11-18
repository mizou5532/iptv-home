import { defineConfig } from 'vitest/config';

export default defineConfig({
  // test-en-attente/ : tests des migrations jamais appliquées en production (voir supabase/en-attente/README.md).
  test: { include: ['test/**/*.test.ts'], testTimeout: 30000, hookTimeout: 60000, fileParallelism: false },
});
