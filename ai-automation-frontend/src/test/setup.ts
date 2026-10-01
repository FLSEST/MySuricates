import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterAll, afterEach, vi } from 'vitest';
import { resetAuthStatusCache, resetRateLimitBackoff } from '../api/client';
import { installNetworkGuards, takeUnhandledRequests, unhandledRequestError } from './msw';

// No real network from a test (1f11b1c3, see ./msw.ts): installed before any msw server starts, so
// the guards are also the pass-through msw falls back to.
installNetworkGuards();

// findBy*/waitFor wait 1 s by default. Under the load of the full suite a MSW answer plus the
// re-render of a whole page regularly takes longer (reproduced with CPU load: a findByRole on the
// projects list failed after 1.7 s, bf4c8b03/4d5d4825). Waiting longer only costs time when a test
// fails anyway; it stays below Vitest's 5 s test timeout so the DOM dump of a real failure still shows.
configure({ asyncUtilTimeout: 4000 });
// Role queries are the costly part of a test here: the first getComputedStyle of a test file parses
// jsdom's default stylesheet (every file runs in its own process, ~0.5-1 s under load), and a role
// query runs that cascade for every element it checks. In loops and polls prefer getByLabelText for
// controls with an aria-label and wait for the request itself rather than polling a role query; a page
// test whose first test still pays the warm-up gets its own timeout at the describe (b26bb8ff).

afterEach(() => {
  cleanup();
  // Module-level state in the API client (429 backoff deadlines, cached auth status)
  // must not leak from one test into the next (d89b2cc1).
  resetRateLimitBackoff();
  resetAuthStatusCache();
  const unhandled = takeUnhandledRequests();
  if (unhandled.length > 0) throw unhandledRequestError(unhandled);
});

// A request that only arrives after the last test still fails the file.
afterAll(() => {
  const unhandled = takeUnhandledRequests();
  if (unhandled.length > 0) throw unhandledRequestError(unhandled);
});

// matchMedia mock
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
});

// ResizeObserver mock
class ResizeObserverMock {
  observe = vi.fn();
  unobserve = vi.fn();
  disconnect = vi.fn();
}
window.ResizeObserver = ResizeObserverMock as unknown as typeof ResizeObserver;

// localStorage mock (jsdom provides one, but ensure it's clearable)
const localStorageMock = (() => {
  let store: Record<string, string> = {};
  return {
    getItem: vi.fn((key: string) => store[key] ?? null),
    setItem: vi.fn((key: string, value: string) => { store[key] = value; }),
    removeItem: vi.fn((key: string) => { delete store[key]; }),
    clear: vi.fn(() => { store = {}; }),
    get length() { return Object.keys(store).length; },
    key: vi.fn((i: number) => Object.keys(store)[i] ?? null),
  };
})();
Object.defineProperty(window, 'localStorage', { value: localStorageMock });
