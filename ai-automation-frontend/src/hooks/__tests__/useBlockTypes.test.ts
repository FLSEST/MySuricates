import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { BLOCK_TYPES } from '../../types';
import { useBlockTypes } from '../useBlockTypes';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => localStorage.clear());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

/** Serves the capabilities response and reports how often it was asked (the suite runs loaded). */
function serve(body: object, status = 200) {
  const seen = { requests: 0 };
  server.use(http.get('/api/v1/system/capabilities', () => {
    seen.requests += 1;
    return HttpResponse.json(body, { status });
  }));
  return seen;
}

function serveBlockTypes(types: string[]) {
  return serve({ routes: [], blockTypes: types.map((type) => ({ type, summary: `${type} summary` })) });
}

describe('useBlockTypes', () => {
  it('uses the block types the server registers, including one this build does not know', async () => {
    serveBlockTypes(['simple', 'shell', 'quantum-block']);
    const { result } = renderHook(() => useBlockTypes(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current).toEqual(['simple', 'shell', 'quantum-block']), { timeout: 5000 });
  });

  it('falls back to the static list while the server has not answered', () => {
    serveBlockTypes(['simple']);
    const { result } = renderHook(() => useBlockTypes(), { wrapper: createWrapper() });

    expect(result.current).toEqual([...BLOCK_TYPES]);
  });

  it('falls back to the static list when the request fails', async () => {
    const seen = serve({ error: 'Rate limit exceeded', timestamp: 'x' }, 429);
    const { result } = renderHook(() => useBlockTypes(), { wrapper: createWrapper() });

    await waitFor(() => expect(seen.requests).toBe(1), { timeout: 5000 });
    expect(result.current).toEqual([...BLOCK_TYPES]);
  });

  it('falls back to the static list when an older backend reports no block types', async () => {
    const seen = serve({ routes: [] });
    const { result } = renderHook(() => useBlockTypes(), { wrapper: createWrapper() });

    await waitFor(() => expect(seen.requests).toBe(1), { timeout: 5000 });
    expect(result.current).toEqual([...BLOCK_TYPES]);
  });

  it('remembers the last known list across reloads, before the fetch completes', async () => {
    serveBlockTypes(['simple', 'shell', 'quantum-block']);
    const first = renderHook(() => useBlockTypes(), { wrapper: createWrapper() });
    await waitFor(() => expect(first.result.current).toHaveLength(3), { timeout: 5000 });

    const { result } = renderHook(() => useBlockTypes(), { wrapper: createWrapper() });
    expect(result.current).toEqual(['simple', 'shell', 'quantum-block']);
  });

  it('still asks the server once when the cache seeded the query', async () => {
    localStorage.setItem('mysuricates-block-types', JSON.stringify(['simple']));
    const seen = serveBlockTypes(['simple', 'shell']);

    const { result } = renderHook(() => useBlockTypes(), { wrapper: createWrapper() });
    expect(result.current).toEqual(['simple']);

    await waitFor(() => expect(result.current).toEqual(['simple', 'shell']), { timeout: 5000 });
    expect(seen.requests).toBe(1);
  });
});
