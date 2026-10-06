import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  // Build-time constants from vite.config.ts, fixed for deterministic tests.
  define: {
    __BUILD_TIMESTAMP__: JSON.stringify('2026-01-01T00:00:00.000Z'),
    // With a suffix, as between two releases — the tile has to cut it off.
    __APP_VERSION__: JSON.stringify('1.0.0-SNAPSHOT'),
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
  resolve: {
    alias: {
      '@': '/src',
    },
  },
});
