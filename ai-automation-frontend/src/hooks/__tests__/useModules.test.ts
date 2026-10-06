import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { modulesFixture } from '../../test/modules';
import { isModuleDisabledError, useModuleEnabled, useSystemModules } from '../useModules';
import { SYSTEM_CAPABILITIES_KEY } from '../useSystemCapabilities';
import { useChains } from '../useChains';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => localStorage.clear());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

function serveModules(disabled: Parameters<typeof modulesFixture>[1], preset: Parameters<typeof modulesFixture>[0] = 'core') {
  server.use(
    http.get('/api/v1/system/capabilities', () => HttpResponse.json({ routes: [], ...modulesFixture(preset, disabled) })),
  );
}

describe('useSystemModules', () => {
  it('extracts preset and modules from the capabilities response', async () => {
    serveModules(['webhooks'], 'research');
    const { result } = renderHook(() => useSystemModules(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.preset).toBe('research');
    expect(result.current.data!.modules.find((m) => m.name === 'webhooks')!.enabled).toBe(false);
  });

  it('treats an older backend without module info as everything enabled', async () => {
    server.use(http.get('/api/v1/system/capabilities', () => HttpResponse.json({ routes: [] })));
    const { result } = renderHook(() => useModuleEnabled('chains'), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current).toBe(true));
  });
});

describe('useModuleEnabled', () => {
  it('is optimistic while the status is unknown', () => {
    const { result } = renderHook(() => useModuleEnabled('chains'), { wrapper: createWrapper() });
    expect(result.current).toBe(true);
  });

  it('reports a module the backend disabled', async () => {
    serveModules(['chains']);
    const { result } = renderHook(() => useModuleEnabled('chains'), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current).toBe(false));
  });
});

describe('gated module queries', () => {
  it('never request a module endpoint once the module is known to be disabled', async () => {
    let chainRequests = 0;
    server.use(http.get('/api/v1/chains', () => {
      chainRequests += 1;
      return HttpResponse.json([]);
    }));
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    // shared capabilities query (a42969a6) — the raw response shape both hooks select from
    qc.setQueryData(SYSTEM_CAPABILITIES_KEY, { routes: [], ...modulesFixture('core', ['chains']) });
    const wrapper = ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useChains(), { wrapper });

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(result.current.fetchStatus).toBe('idle');
    expect(result.current.data).toBeUndefined();
    expect(chainRequests).toBe(0);
  });

  // b13509f3: placeholderData only applies in v5 when status === 'pending'. After a 429 every
  // module counted as "on" again and navigation/widgets flickered.
  it('keep the cached module state when the request fails with 429', async () => {
    localStorage.setItem('mysuricates-modules', JSON.stringify(modulesFixture('core', ['chains'])));
    server.use(http.get('/api/v1/system/capabilities', () => HttpResponse.json(
      { error: 'Rate limit exceeded', timestamp: 'x' },
      { status: 429, headers: { 'Retry-After': '5' } },
    )));

    const { result } = renderHook(() => useSystemModules(), { wrapper: createWrapper() });
    expect(result.current.data?.modules.find((m) => m.name === 'chains')!.enabled).toBe(false);

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data?.modules.find((m) => m.name === 'chains')!.enabled).toBe(false);
  });

  it('still asks the server once when the cache seeded the query', async () => {
    localStorage.setItem('mysuricates-modules', JSON.stringify(modulesFixture('core', ['chains'])));
    let requests = 0;
    server.use(http.get('/api/v1/system/capabilities', () => {
      requests += 1;
      return HttpResponse.json({ routes: [], ...modulesFixture('research', []) });
    }));

    const { result } = renderHook(() => useSystemModules(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.data!.preset).toBe('research'));
    expect(requests).toBe(1);
  });

  it('remember the last known modules across reloads to avoid a first-request 404', async () => {
    serveModules(['chains']);
    const first = renderHook(() => useSystemModules(), { wrapper: createWrapper() });
    await waitFor(() => expect(first.result.current.isSuccess).toBe(true));

    // a fresh client (page reload) starts from the cached placeholder before the fetch completes
    const { result } = renderHook(() => useModuleEnabled('chains'), { wrapper: createWrapper() });
    expect(result.current).toBe(false);
  });
});

describe('isModuleDisabledError', () => {
  it('recognises the backend error code', () => {
    expect(isModuleDisabledError({ code: 'MODULE_DISABLED', status: 404 })).toBe(true);
    expect(isModuleDisabledError({ code: 'RESOURCE_NOT_FOUND', status: 404 })).toBe(false);
    expect(isModuleDisabledError(null)).toBe(false);
  });
});
