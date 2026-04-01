import { defineConfig } from 'cypress';
import { resolve, dirname } from 'path';
import { existsSync, mkdirSync } from 'fs';
import { execSync } from 'child_process';
import { fileURLToPath } from 'url';

type E2ETarget = 'dev' | 'test';

const __dir = dirname(fileURLToPath(import.meta.url));

function resolveTarget(value: string | undefined): E2ETarget {
  return value === 'test' ? 'test' : 'dev';
}

function detectApiKeyFromSqlite(dbPath: string): string {
  try {
    if (!existsSync(dbPath)) {
      return '';
    }

    const script = `import sqlite3,sys;c=sqlite3.connect(sys.argv[1]);row=c.execute("SELECT value FROM app_settings WHERE key=?",("api-key",)).fetchone();print(row[0] if row else "")`;
    return execSync(`python3 -c '${script}' '${dbPath}'`, {
      encoding: 'utf-8',
      timeout: 5000,
    }).trim();
  } catch {
    return '';
  }
}

const e2eTarget = resolveTarget(process.env.CYPRESS_TARGET);
const defaultApiOrigin = e2eTarget === 'test' ? 'http://localhost:8090' : 'http://localhost:9090';
const defaultWsOrigin = e2eTarget === 'test' ? 'ws://localhost:8090' : 'ws://localhost:9090';
const devDbPath = resolve(__dir, '../ai-automation-backend/data/automation.db');

const detectedApiKey =
  process.env.CYPRESS_API_KEY?.trim() ||
  (e2eTarget === 'test'
    ? process.env.CYPRESS_TEST_API_KEY?.trim() || process.env.AUTOMATION_API_KEY?.trim() || ''
    : process.env.CYPRESS_DEV_API_KEY?.trim() ||
      detectApiKeyFromSqlite(devDbPath) ||
      process.env.AUTOMATION_API_KEY?.trim() ||
      '');

const apiBaseUrl = (process.env.CYPRESS_API_BASE_URL?.trim() || `${defaultApiOrigin}/api/v1`).replace(/\/$/, '');
const wsBaseUrl = (process.env.CYPRESS_WS_BASE_URL?.trim() || defaultWsOrigin).replace(/\/$/, '');

if (!detectedApiKey) {
  // No API key detected: tests can still run if auth is disabled on the target instance.
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
    env: {
      E2E_TARGET: e2eTarget,
      API_BASE_URL: apiBaseUrl,
      WS_BASE_URL: wsBaseUrl,
      ...(detectedApiKey ? { API_KEY: detectedApiKey } : {}),
    },
    setupNodeEvents(on) {
      on('before:run', () => {
        // Ensure the test project directory exists (PathValidator requires it)
        mkdirSync('/tmp/cypress-test', { recursive: true });
      });
    },
  },
});
