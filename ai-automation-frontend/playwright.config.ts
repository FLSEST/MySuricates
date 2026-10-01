import { defineConfig } from '@playwright/test';
import { readApiKeyFile } from './playwright/fixtures/api-key';

// Never read the SQLite DB directly (CLAUDE.md: no direct DB access, pool-size=1 lock
// contention). The backend writes the effective key to a plaintext file on the host at every
// start (ApiKeyService/InstanceAddress) — read that instead (same order as cypress.config.ts).
const detectedApiKey = readApiKeyFile();

if (!detectedApiKey) {
  // No API key detected: tests can still run if auth is disabled on the target instance
  // (AUTOMATION_SECURITY_ENABLED=false). Otherwise every authenticated request fails.
  console.warn(
    '[playwright.config] No API key found (~/.mysuricates/api-key, older: ~/.autotrigger/api-key). ' +
      'Start the dev backend first so it writes the key file, or run against an instance with ' +
      'AUTOMATION_SECURITY_ENABLED=false.',
  );
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
