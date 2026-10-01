import type { UnhandledRequestCallback } from 'msw';

// No test may reach a real backend (1f11b1c3). A relative `/api/v1/...` fetch in jsdom goes to
// http://localhost:3000 — with a running `npm run dev` that is the Vite proxy in front of the dev
// backend, writing requests included. Two layers keep that from happening:
//
// 1. every msw server listens with `onUnhandledRequest: failOnUnhandledRequest`: a request without
//    a handler is recorded and halted (msw answers it with a 500 instead of passing it on);
// 2. `src/test/setup.ts` calls `installNetworkGuards()` before any msw server starts: fetch is
//    replaced, XHR `send` and WebSocket connections (SockJS of the socket hooks) are blocked. msw keeps
//    the fetch/XHR it found as its pass-through, so even a server that bypasses — or a test without
//    any server — ends up in a guard, which records the request and fails it.
//
// Every recorded request fails the test it happened in (afterEach in setup.ts), or the file when
// it arrives after the last test (afterAll). Add the missing handler; never switch back to 'bypass'.

const unhandled: string[] = [];

function describeRequest(method: string, url: string): string {
  return `${method.toUpperCase()} ${url}`;
}

export function recordUnhandledRequest(method: string, url: string): void {
  unhandled.push(describeRequest(method, url));
}

/** Hands out (and forgets) the requests recorded since the last call. */
export function takeUnhandledRequests(): string[] {
  return unhandled.splice(0, unhandled.length);
}

export function unhandledRequestError(requests: string[]): Error {
  return new Error(
    `Request(s) without a msw handler — tests must not reach a real backend:\n  ${requests.join('\n  ')}\n`
      + 'Add a handler (server.use(...) or src/test/handlers.ts) for each of them.',
  );
}

/**
 * `onUnhandledRequest` strategy for every msw server: record, print, halt. msw 2.15's `print.error()`
 * only prints — a callback strategy halts the request only by throwing; returning means pass-through.
 */
export const failOnUnhandledRequest: UnhandledRequestCallback = (request, print) => {
  recordUnhandledRequest(request.method, request.url);
  print.error();
  throw new TypeError(`No msw handler for ${describeRequest(request.method, request.url)}`);
};

/** The fetch msw finds when it starts: never goes to the network. */
export const blockedFetch: typeof fetch = async (input, init) => {
  const method = init?.method ?? (input instanceof Request ? input.method : 'GET');
  const raw = input instanceof Request ? input.url : String(input);
  const url = new URL(raw, globalThis.location?.href ?? 'http://localhost/').href;
  recordUnhandledRequest(method, url);
  throw new TypeError(`Blocked real network request in a test: ${describeRequest(method, url)}`);
};

/**
 * Blocks every way out of the test process: fetch, XHR and WebSocket. XHR is patched on the
 * prototype so that msw's pass-through (which drives a real jsdom XHR) is caught as well.
 */
export function installNetworkGuards(): void {
  globalThis.fetch = blockedFetch;

  const targets = new WeakMap<XMLHttpRequest, string>();
  const open = XMLHttpRequest.prototype.open as (this: XMLHttpRequest, ...args: unknown[]) => void;
  XMLHttpRequest.prototype.open = function guardedOpen(this: XMLHttpRequest, ...args: unknown[]) {
    const [method, url] = args as [string, string | URL];
    targets.set(this, describeRequest(method, new URL(String(url), globalThis.location.href).href));
    open.apply(this, args);
  } as typeof XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.send = function guardedSend(this: XMLHttpRequest) {
    const target = targets.get(this) ?? 'XHR (unknown target)';
    unhandled.push(target);
    throw new DOMException(`Blocked real network request in a test: ${target}`, 'NetworkError');
  };

  const RealWebSocket = globalThis.WebSocket;
  if (RealWebSocket) {
    // A subclass keeps the static constants (WebSocket.OPEN …); the argument check throws before
    // the real constructor could connect.
    globalThis.WebSocket = class BlockedWebSocket extends RealWebSocket {
      constructor(url: string | URL, protocols?: string | string[]) {
        super(blockWebSocket(url), protocols);
      }
    };
  }
}

function blockWebSocket(url: string | URL): string {
  const target = new URL(String(url), globalThis.location.href).href;
  recordUnhandledRequest('WEBSOCKET', target);
  throw new DOMException(`Blocked real WebSocket in a test: ${target}`, 'NetworkError');
}
