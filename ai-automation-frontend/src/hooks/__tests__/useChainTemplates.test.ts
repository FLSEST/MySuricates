import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useI18nStore } from '../../i18n';
import { useChainTemplates, useInstallChainTemplate } from '../useChainTemplates';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
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

  it('installs in the UI language', async () => {
    useI18nStore.setState({ locale: 'en' });
    let body: unknown = null;
    server.use(
      http.post('/api/v1/chain-templates/:templateId/install', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ chainId: 'c1', chainName: 'Chain', workflowIds: [] });
      }),
    );
    const { result } = renderHook(() => useInstallChainTemplate(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ templateId: 'ct1', projectId: 'p1' });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(body).toMatchObject({ projectId: 'p1', locale: 'en' });
  });
});
