import { defineConfig } from 'cypress';
import { resolve, dirname } from 'path';
import { existsSync, mkdirSync } from 'fs';
import { execSync } from 'child_process';
import { fileURLToPath } from 'url';

// Auto-detect API key from SQLite DB
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
  // DB not available — tests will run without API key (security disabled)
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
