import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useChainTemplates, useInstallChainTemplate } from '../useChainTemplates';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useChainTemplates', () => {
  it('fetches chain templates', async () => {
    server.use(
      http.get('/api/v1/chain-templates', () =>
        HttpResponse.json([{ id: 'ct1', name: 'Template 1' }])),
    );
    const { result } = renderHook(() => useChainTemplates(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/chain-templates', () => HttpResponse.json([])),
    );
    const { result } = renderHook(() => useChainTemplates(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useInstallChainTemplate', () => {
  it('installs a chain template', async () => {
    server.use(
      http.post('/api/v1/chain-templates/:templateId/install', () =>
        HttpResponse.json({ chainId: 'c1', chainName: 'Installed Chain', workflowIds: ['w1'] })),
    );
    const { result } = renderHook(() => useInstallChainTemplate(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ templateId: 'ct1', projectId: 'p1' });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.chainId).toBe('c1');
  });
});
