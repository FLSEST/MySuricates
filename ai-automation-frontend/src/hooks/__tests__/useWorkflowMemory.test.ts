import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useWorkflowMemories, useExtractMemory, useActivateMemory, useDeleteMemory } from '../useWorkflowMemory';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useWorkflowMemories', () => {
  it('fetches memories for a workflow', async () => {
    server.use(
      http.get('/api/v1/workflows/:wfId/memory', () =>
        HttpResponse.json([{ id: 'm1', title: 'Memory 1', content: 'test', active: true }])),
    );
    const { result } = renderHook(() => useWorkflowMemories('wf1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data![0].id).toBe('m1');
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/workflows/:wfId/memory', () => HttpResponse.json([])),
    );
    const { result } = renderHook(() => useWorkflowMemories('wf1'), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });

  it('does not fetch when workflowId is empty', () => {
    const { result } = renderHook(() => useWorkflowMemories(''), { wrapper: createWrapper() });
    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('useExtractMemory', () => {
  it('extracts memory via mutation', async () => {
    server.use(
      http.post('/api/v1/workflows/:wfId/memory/extract', () =>
        HttpResponse.json({ id: 'm2', title: 'Extracted', content: 'new', active: true })),
    );
    const { result } = renderHook(() => useExtractMemory('wf1'), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ runId: 'r1' } as any);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('m2');
  });
});

describe('useActivateMemory', () => {
  it('activates a memory', async () => {
    server.use(
      http.put('/api/v1/workflows/:wfId/memory/:memId/activate', () =>
        HttpResponse.json({})),
    );
    const { result } = renderHook(() => useActivateMemory('wf1'), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate('m1');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useDeleteMemory', () => {
  it('deletes a memory', async () => {
    server.use(
      http.delete('/api/v1/workflows/:wfId/memory/:memId', () =>
        new HttpResponse(null, { status: 204 })),
    );
    const { result } = renderHook(() => useDeleteMemory('wf1'), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate('m1');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
