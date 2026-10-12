/**
 * The E2E target (dev on :9090 or test on :8090, see cypress.config.ts).
 *
 * Cypress 16 removed `Cypress.env()`: public values come from the config's `expose` block through
 * `Cypress.expose()`, sensitive ones only through the command `cy.env()`. The API key is sensitive,
 * so e2e.ts reads it once per spec (a root `before` hook) and keeps it here for the places that
 * cannot run a command: the API helpers build their request headers synchronously, and
 * `window:before:load` is an event handler.
 */

const DEFAULT_API = 'http://localhost:9090/api/v1';

// The support file and every spec are separate bundles, each with its own copy of this module.
// A module-level variable set by e2e.ts would stay empty in a spec that imports the API helpers
// directly (chains.cy.ts got 403 on its cleanup requests), so the key lives on the shared window.
const API_KEY_SLOT = '__mysuricatesE2eApiKey';

export function setApiKey(key: unknown): void {
  (globalThis as Record<string, unknown>)[API_KEY_SLOT] = typeof key === 'string' ? key : '';
}

/** Empty when the target runs without an API key. */
export function apiKey(): string {
  const value = (globalThis as Record<string, unknown>)[API_KEY_SLOT];
  return typeof value === 'string' ? value : '';
}

export function apiBaseUrl(): string {
  return String(Cypress.expose('API_BASE_URL') || DEFAULT_API).replace(/\/$/, '');
}
