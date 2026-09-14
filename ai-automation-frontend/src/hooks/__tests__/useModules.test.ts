import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { modulesFixture } from '../../test/modules';
import { isModuleDisabledError, useModuleEnabled, useSystemModules } from '../useModules';
import { useChains } from '../useChains';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
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
    qc.setQueryData(['system', 'modules'], modulesFixture('core', ['chains']));
    const wrapper = ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useChains(), { wrapper });

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(result.current.fetchStatus).toBe('idle');
    expect(result.current.data).toBeUndefined();
    expect(chainRequests).toBe(0);
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
