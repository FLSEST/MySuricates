import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { blockedFetch, failOnUnhandledRequest, takeUnhandledRequests, unhandledRequestError } from '../msw';

// 1f11b1c3: no test may reach a real backend — a relative /api/v1 fetch would go through the Vite
// proxy to the running dev instance. Each test takes what it provoked, so the afterEach guard of
// setup.ts only sees what slips through.

describe('without a msw server', () => {
  it('setup.ts replaced fetch: a request is rejected and recorded instead of sent', async () => {
    expect(globalThis.fetch).toBe(blockedFetch);

    await expect(fetch('/api/v1/todos', { method: 'POST', body: '{}' })).rejects.toThrow(/Blocked real network request/);

    expect(takeUnhandledRequests()).toEqual(['POST http://localhost:3000/api/v1/todos']);
  });

  it('blocks an XHR the same way', () => {
    const xhr = new XMLHttpRequest();
    xhr.open('DELETE', '/api/v1/workflows/w1');

    expect(() => xhr.send()).toThrow(/Blocked real network request/);
    expect(takeUnhandledRequests()).toEqual(['DELETE http://localhost:3000/api/v1/workflows/w1']);
  });

  it('blocks a WebSocket connection (the socket hooks connect through SockJS)', () => {
    expect(() => new WebSocket('ws://localhost:3000/ws')).toThrow(/Blocked real WebSocket/);
    expect(takeUnhandledRequests()).toEqual(['WEBSOCKET ws://localhost:3000/ws']);
    expect(WebSocket.OPEN).toBe(1);
  });

  it('names every recorded request in the error that fails the test', () => {
    const error = unhandledRequestError(['GET http://localhost:3000/api/v1/goals', 'DELETE http://localhost:3000/api/v1/todos/1']);

    expect(error.message).toContain('GET http://localhost:3000/api/v1/goals');
    expect(error.message).toContain('DELETE http://localhost:3000/api/v1/todos/1');
  });
});

describe('with a msw server', () => {
  const server = setupServer(http.get('/api/v1/handled', () => HttpResponse.json({ ok: true })));
  beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
  afterEach(() => server.resetHandlers());
  afterAll(() => server.close());

  it('answers a request that has a handler', async () => {
    const response = await fetch('/api/v1/handled');

    expect(await response.json()).toEqual({ ok: true });
    expect(takeUnhandledRequests()).toEqual([]);
  });

  it('halts a request without a handler and records it once', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    let status: number | 'rejected';
    try {
      // msw answers the exception of the strategy with a 500 of its own
      status = await fetch('/api/v1/workflows/w1/execute', { method: 'POST' }).then((r) => r.status, () => 'rejected');
    } finally {
      consoleError.mockRestore();
    }

    expect(status).not.toBe(200);
    // Once: a pass-through would have reached the blocked fetch and recorded it a second time
    expect(takeUnhandledRequests()).toEqual(['POST http://localhost:3000/api/v1/workflows/w1/execute']);
  });

  it('also stops a server that bypasses: its pass-through is the blocked fetch', async () => {
    server.close();
    server.listen({ onUnhandledRequest: 'bypass' });
    try {
      await expect(fetch('/api/v1/goals')).rejects.toThrow();
    } finally {
      server.close();
      server.listen({ onUnhandledRequest: failOnUnhandledRequest });
    }

    expect(takeUnhandledRequests()).toEqual(['GET http://localhost:3000/api/v1/goals']);
  });

  it('answers an XHR that has a handler and stops one without', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const load = (url: string) => new Promise<number>((resolve) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', url);
      xhr.onloadend = () => resolve(xhr.status);
      xhr.send();
    });
    try {
      expect(await load('/api/v1/handled')).toBe(200);
      expect(await load('/api/v1/unhandled')).not.toBe(200);
    } finally {
      consoleError.mockRestore();
    }

    expect(takeUnhandledRequests()).toEqual(['GET http://localhost:3000/api/v1/unhandled']);
  });
});
