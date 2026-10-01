import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { modulesFixture } from '../../test/modules';
import { useSystemModules } from '../useModules';
import { useBlockTypes } from '../useBlockTypes';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => localStorage.clear());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

/**
 * TODO a42969a6: beide Hooks lesen denselben (grossen) Endpunkt. Auf einer Instanz mit
 * Rate-Limit zaehlt jeder doppelte Abruf, also teilen sie sich einen Query.
 */
describe('system capabilities', () => {
  it('serves useSystemModules and useBlockTypes from a single request', async () => {
    let requests = 0;
    server.use(http.get('/api/v1/system/capabilities', () => {
      requests += 1;
      return HttpResponse.json({
        routes: [],
        blockTypes: [{ type: 'simple' }, { type: 'shell' }],
        ...modulesFixture('core', ['chains']),
      });
    }));
    const wrapper = createWrapper();

    const { result } = renderHook(
      () => ({ modules: useSystemModules(), blockTypes: useBlockTypes() }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.modules.isSuccess).toBe(true), { timeout: 5000 });
    await waitFor(() => expect(result.current.blockTypes).toEqual(['simple', 'shell']), { timeout: 5000 });
    expect(result.current.modules.data!.modules.find((m) => m.name === 'chains')!.enabled).toBe(false);
    expect(requests).toBe(1);
  });
});
