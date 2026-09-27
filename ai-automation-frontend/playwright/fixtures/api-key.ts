/**
 * Reads the API key from the plaintext key file the backend writes on every start
 * (ApiKeyService/InstanceAddress) — never from the SQLite DB (CLAUDE.md: no direct DB access,
 * pool-size=1 lock contention). Same candidate paths/order as cypress.config.ts (commit
 * 2e2f10a6) and scripts/start-autotrigger-dev.sh's `api_key()`: `~/.mysuricates/api-key` first,
 * then `~/.autotrigger/api-key`.
 *
 * Shared by playwright.config.ts and playwright/fixtures/api-helpers.ts so both look the key up
 * the same way instead of duplicating the file-read logic.
 */

import { resolve } from 'path';
import { readFileSync } from 'fs';

export function readApiKeyFile(): string {
  const home = process.env.HOME?.trim();
  if (!home) {
    return '';
  }
  const candidates = [resolve(home, '.mysuricates/api-key'), resolve(home, '.autotrigger/api-key')];
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
