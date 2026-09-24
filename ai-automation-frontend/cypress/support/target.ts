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

let apiKeyValue = '';

export function setApiKey(key: unknown): void {
  apiKeyValue = typeof key === 'string' ? key : '';
}

/** Empty when the target runs without an API key. */
export function apiKey(): string {
  return apiKeyValue;
}

export function apiBaseUrl(): string {
  return String(Cypress.expose('API_BASE_URL') || DEFAULT_API).replace(/\/$/, '');
}
