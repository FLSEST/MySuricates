import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { usePoolWorkflows, useInstallPoolWorkflow } from '../usePool';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('usePoolWorkflows', () => {
  it('fetches pool workflows', async () => {
    server.use(
      http.get('/api/v1/pool', () =>
        HttpResponse.json([{ id: 'p1', name: 'Pool Workflow 1' }])),
    );
    const { result } = renderHook(() => usePoolWorkflows(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/pool', () => HttpResponse.json([])),
    );
    const { result } = renderHook(() => usePoolWorkflows(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useInstallPoolWorkflow', () => {
  it('installs a pool workflow', async () => {
    server.use(
      http.post('/api/v1/pool/:poolId/install', () =>
        HttpResponse.json({ workflowId: 'w1', workflowName: 'Installed', created: true })),
    );
    const { result } = renderHook(() => useInstallPoolWorkflow(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ poolId: 'p1', projectId: 'proj1' });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.workflowId).toBe('w1');
  });
});
