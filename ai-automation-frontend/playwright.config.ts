import { defineConfig } from '@playwright/test';
import { resolve, dirname } from 'path';
import { existsSync } from 'fs';
import { execSync } from 'child_process';
import { fileURLToPath } from 'url';

// Auto-detect API key from SQLite DB (same pattern as cypress.config.ts)
let detectedApiKey = '';
try {
  const __dir = dirname(fileURLToPath(import.meta.url));
  const dbPath = resolve(__dir, '../ai-automation-backend/data/automation.db');
  if (existsSync(dbPath)) {
    const script = `import sqlite3,sys;c=sqlite3.connect(sys.argv[1]);print(c.execute("SELECT value FROM app_settings WHERE key=?",("api-key",)).fetchone()[0])`;
    detectedApiKey = execSync(`python3 -c '${script}' '${dbPath}'`, {
      encoding: 'utf-8',
      timeout: 5000,
    }).trim();
  }
} catch {
  // DB not available — tests will run without API key
}

export default defineConfig({
  testDir: './playwright/tests',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [
    ['list'],
    ['json', { outputFile: 'playwright/results/report.json' }],
  ],
  use: {
    baseURL: 'http://localhost:3000',
    trace: 'off',
    screenshot: 'off', // We take manual screenshots
  },
  projects: [
    {
      name: 'setup',
      testMatch: /auth\.setup\.ts/,
    },
    {
      name: 'shell-templates',
      testMatch: /shell-templates\.spec\.ts/,
      dependencies: ['setup'],
      timeout: 5 * 60 * 1000, // 5 min per test
      use: { storageState: 'playwright/.auth/state.json' },
    },
    {
      name: 'simple-ai-templates',
      testMatch: /simple-ai-templates\.spec\.ts/,
      dependencies: ['setup'],
      timeout: 10 * 60 * 1000, // 10 min per test
      use: { storageState: 'playwright/.auth/state.json' },
    },
  ],
  // Export API key for fixtures
  ...(detectedApiKey ? { metadata: { apiKey: detectedApiKey } } : {}),
});

export { detectedApiKey };
