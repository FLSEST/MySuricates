import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import {
  useCompletionTriggersAsSource,
  useCompletionTriggersAsTarget,
  useCreateCompletionTrigger,
  useToggleCompletionTrigger,
  useDeleteCompletionTrigger,
} from '../useCompletionTriggers';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useCompletionTriggersAsSource', () => {
  it('fetches triggers where workflow is source', async () => {
    server.use(
      http.get('/api/v1/completion-triggers/workflow/:id/as-source', () =>
        HttpResponse.json([{ id: 'ct1', sourceWorkflowId: 'wf1', targetWorkflowId: 'wf2', enabled: true }])),
    );
    const { result } = renderHook(() => useCompletionTriggersAsSource('wf1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data![0].id).toBe('ct1');
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/completion-triggers/workflow/:id/as-source', () =>
        HttpResponse.json([])),
    );
    const { result } = renderHook(() => useCompletionTriggersAsSource('wf1'), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useCompletionTriggersAsTarget', () => {
  it('fetches triggers where workflow is target', async () => {
    server.use(
      http.get('/api/v1/completion-triggers/workflow/:id/as-target', () =>
        HttpResponse.json([{ id: 'ct2', sourceWorkflowId: 'wf3', targetWorkflowId: 'wf1', enabled: true }])),
    );
    const { result } = renderHook(() => useCompletionTriggersAsTarget('wf1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
  });
});

describe('useCreateCompletionTrigger', () => {
  it('creates a trigger via mutation', async () => {
    server.use(
      http.post('/api/v1/completion-triggers', () =>
        HttpResponse.json({ id: 'ct3', sourceWorkflowId: 'wf1', targetWorkflowId: 'wf2', enabled: true })),
    );
    const { result } = renderHook(() => useCreateCompletionTrigger(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ sourceWorkflowId: 'wf1', targetWorkflowId: 'wf2' } as any);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('ct3');
  });
});

describe('useToggleCompletionTrigger', () => {
  it('toggles a trigger via mutation', async () => {
    server.use(
      http.post('/api/v1/completion-triggers/:id/enable', () =>
        HttpResponse.json({ id: 'ct1', enabled: true })),
    );
    const { result } = renderHook(() => useToggleCompletionTrigger('wf1'), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ id: 'ct1', enable: true });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useDeleteCompletionTrigger', () => {
  it('deletes a trigger via mutation', async () => {
    server.use(
      http.delete('/api/v1/completion-triggers/:id', () =>
        new HttpResponse(null, { status: 204 })),
    );
    const { result } = renderHook(() => useDeleteCompletionTrigger('wf1'), { wrapper: createWrapper() });
    await act(async () => { result.current.mutate('ct1'); });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
