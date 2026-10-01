import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import {
  useFactoryTopics,
  useFactoryGenerate,
  useFactoryRun,
  useFactoryVerify,
  useFactoryPromote,
} from '../useWorkflowFactory';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useFactoryTopics', () => {
  it('fetches factory topics', async () => {
    server.use(
      http.get('/api/v1/factory/topics', () =>
        HttpResponse.json([{ category: 'dev', topics: ['lint', 'test'] }])),
    );
    const { result } = renderHook(() => useFactoryTopics(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data![0].category).toBe('dev');
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/factory/topics', () => HttpResponse.json([])),
    );
    const { result } = renderHook(() => useFactoryTopics(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useFactoryGenerate', () => {
  it('generates workflow via mutation', async () => {
    server.use(
      http.post('/api/v1/factory/generate', () =>
        HttpResponse.json({ id: 'wf1', name: 'Generated' })),
    );
    const { result } = renderHook(() => useFactoryGenerate(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ topic: 'lint', projectId: 'p1' } as any);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('wf1');
  });
});

describe('useFactoryRun', () => {
  it('runs factory via mutation', async () => {
    server.use(
      http.post('/api/v1/factory/run', () =>
        HttpResponse.json({ workflowId: 'wf1', runId: 'r1', status: 'COMPLETED' })),
    );
    const { result } = renderHook(() => useFactoryRun(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ topic: 'test', projectId: 'p1' } as any);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.status).toBe('COMPLETED');
  });
});

describe('useFactoryVerify', () => {
  it('verifies workflow via mutation', async () => {
    server.use(
      http.post('/api/v1/factory/verify/:id', () =>
        HttpResponse.json({ workflowId: 'wf1', status: 'VERIFIED' })),
    );
    const { result } = renderHook(() => useFactoryVerify(), { wrapper: createWrapper() });
    await act(async () => { result.current.mutate('wf1'); });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useFactoryPromote', () => {
  it('promotes workflow via mutation', async () => {
    server.use(
      http.post('/api/v1/factory/promote/:id', () =>
        HttpResponse.json({ workflowId: 'wf1', status: 'PROMOTED' })),
    );
    const { result } = renderHook(() => useFactoryPromote(), { wrapper: createWrapper() });
    await act(async () => { result.current.mutate('wf1'); });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
