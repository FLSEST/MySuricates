import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useWorkflowVersions, useRestoreWorkflowVersion } from '../useWorkflowVersions';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useWorkflowVersions', () => {
  it('fetches versions for a workflow', async () => {
    server.use(
      http.get('/api/v1/workflows/:wfId/versions', () =>
        HttpResponse.json({ content: [{ id: 'v1', version: 1 }], totalElements: 1 })),
    );
    const { result } = renderHook(() => useWorkflowVersions('wf1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.content).toHaveLength(1);
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/workflows/:wfId/versions', () =>
        HttpResponse.json({ content: [], totalElements: 0 })),
    );
    const { result } = renderHook(() => useWorkflowVersions('wf1'), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });

  it('does not fetch when workflowId is empty', () => {
    const { result } = renderHook(() => useWorkflowVersions(''), { wrapper: createWrapper() });
    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('useRestoreWorkflowVersion', () => {
  it('restores a version', async () => {
    server.use(
      http.post('/api/v1/workflows/:wfId/versions/:vId/restore', () =>
        HttpResponse.json({ id: 'wf1', name: 'Restored' })),
    );
    const { result } = renderHook(() => useRestoreWorkflowVersion('wf1'), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate('v1');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
