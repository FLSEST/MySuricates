import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useRunInsights } from '../useInsights';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useRunInsights', () => {
  it('fetches insights for a run', async () => {
    server.use(
      http.get('/api/v1/learning/runs/:runId/insights', () =>
        HttpResponse.json({ summary: 'All good', suggestions: [] })),
    );
    const { result } = renderHook(() => useRunInsights('r1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.summary).toBe('All good');
  });

  it('returns loading state initially', () => {
    server.use(
      http.get('/api/v1/learning/runs/:runId/insights', () =>
        HttpResponse.json({ summary: '', suggestions: [] })),
    );
    const { result } = renderHook(() => useRunInsights('r1'), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });

  it('does not fetch when runId is empty', () => {
    const { result } = renderHook(() => useRunInsights(''), { wrapper: createWrapper() });
    expect(result.current.fetchStatus).toBe('idle');
  });
});
