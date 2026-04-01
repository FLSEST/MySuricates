import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useDashboard, useRateRun } from '../useLearning';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useDashboard', () => {
  it('fetches learning dashboard', async () => {
    server.use(
      http.get('/api/v1/learning/dashboard', () =>
        HttpResponse.json({ totalRuns: 10, successRate: 0.85 })),
    );
    const { result } = renderHook(() => useDashboard(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.totalRuns).toBe(10);
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/learning/dashboard', () => HttpResponse.json({})),
    );
    const { result } = renderHook(() => useDashboard(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useRateRun', () => {
  it('rates a run', async () => {
    server.use(
      http.post('/api/v1/learning/runs/:runId/rate', () =>
        new HttpResponse(null, { status: 204 })),
    );
    const { result } = renderHook(() => useRateRun(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ runId: 'r1', rating: 5 });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
