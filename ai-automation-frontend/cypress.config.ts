import { defineConfig } from 'cypress';
import { resolve } from 'path';
import { mkdirSync, readFileSync } from 'fs';

type E2ETarget = 'dev' | 'test';

function resolveTarget(value: string | undefined): E2ETarget {
  return value === 'test' ? 'test' : 'dev';
}

// Never read the SQLite DB directly (CLAUDE.md: "NEVER access the SQLite database directly …
// not from scripts", pool-size=1 lock contention). The backend (ApiKeyService) writes the
// effective key to a plaintext file in its data directory at startup — read that instead. Same
// rule as scripts/lib/data-home.sh: `~/.autotrigger` while an older installation still has it,
// otherwise `~/.mysuricates`.
function readApiKeyFile(): string {
  const home = process.env.HOME?.trim();
  if (!home) {
    return '';
  }
  const candidates = [resolve(home, '.autotrigger/api-key'), resolve(home, '.mysuricates/api-key')];
  for (const file of candidates) {
    try {
      const content = readFileSync(file, 'utf-8').trim();
      if (content) {
        return content;
      }
    } catch {
      // File missing or unreadable — try the next candidate.
    }
  }
  return '';
}

const e2eTarget = resolveTarget(process.env.CYPRESS_TARGET);
const defaultApiOrigin = e2eTarget === 'test' ? 'http://localhost:8090' : 'http://localhost:9090';
const defaultWsOrigin = e2eTarget === 'test' ? 'ws://localhost:8090' : 'ws://localhost:9090';

const detectedApiKey =
  process.env.CYPRESS_API_KEY?.trim() ||
  (e2eTarget === 'test'
    ? process.env.CYPRESS_TEST_API_KEY?.trim() || process.env.AUTOMATION_API_KEY?.trim() || ''
    : process.env.CYPRESS_DEV_API_KEY?.trim() ||
      readApiKeyFile() ||
      process.env.AUTOMATION_API_KEY?.trim() ||
      '');

const apiBaseUrl = (process.env.CYPRESS_API_BASE_URL?.trim() || `${defaultApiOrigin}/api/v1`).replace(/\/$/, '');
const wsBaseUrl = (process.env.CYPRESS_WS_BASE_URL?.trim() || defaultWsOrigin).replace(/\/$/, '');

if (!detectedApiKey) {
  // No API key detected: tests can still run if auth is disabled on the target instance
  // (AUTOMATION_SECURITY_ENABLED=false). Otherwise every authenticated request fails —
  // there is no key-free way to fetch it (GET /api/v1/system/settings/api-key itself needs
  // the key). Set one explicitly before running:
  //   export CYPRESS_API_KEY="$(cat ~/.mysuricates/api-key)"   # dev instance on this host (older: ~/.autotrigger)
  //   export CYPRESS_TEST_API_KEY="$(docker exec <container> cat /home/<user>/.mysuricates/api-key)"  # test instance
  console.warn(
    '[cypress.config] No API key found (CYPRESS_API_KEY / ~/.mysuricates/api-key). ' +
      'Set CYPRESS_API_KEY to the key from `cat ~/.mysuricates/api-key`, or run against an ' +
      'instance with AUTOMATION_SECURITY_ENABLED=false.',
  );
}

export default defineConfig({
  e2e: {
    baseUrl: 'http://localhost:3000',
    supportFile: 'cypress/support/e2e.ts',
    specPattern: 'cypress/e2e/**/*.cy.ts',
    viewportWidth: 1280,
    viewportHeight: 800,
    defaultCommandTimeout: 10000,
    video: false,
    retries: {
      runMode: 1,
      openMode: 0,
    },
    // Cypress 16: public values via `expose` (Cypress.expose), sensitive ones via `env` (cy.env only).
    expose: {
      E2E_TARGET: e2eTarget,
      API_BASE_URL: apiBaseUrl,
      WS_BASE_URL: wsBaseUrl,
    },
    env: {
      // Always set (empty without a key), so cy.env(['API_KEY']) finds it on targets without auth too.
      API_KEY: detectedApiKey,
    },
    setupNodeEvents(on) {
      on('before:run', () => {
        // Ensure the test project directory exists (PathValidator requires it)
        mkdirSync('/tmp/cypress-test', { recursive: true });
      });
    },
  },
});
