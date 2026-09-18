import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { server } from '../../test/server';
import { useWorkflowWidgetResults } from '../useWorkflowWidgets';

const ENTRY = {
  workflowId: 'wf-a1', workflowName: 'Nightly QA', available: true,
  run: { runId: 'run-1', status: 'COMPLETED', startedAt: '2026-09-18T08:00:00Z', completedAt: null, durationMs: 1200, gateStatus: 'GREEN' },
  resultState: 'VALID', result: { status: 'GREEN', items: ['ok'] },
};

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('useWorkflowWidgetResults', () => {
  it('asks once for all ids and returns only entries that pass the contract check', async () => {
    const urls: string[] = [];
    server.use(http.get('/api/v1/dashboard/workflow-results', ({ request }) => {
      urls.push(new URL(request.url).search);
      return HttpResponse.json([ENTRY, { workflowId: 'NOT AN ID' }, { ...ENTRY, workflowId: 'wf-b1', result: { status: 'BLUE' } }]);
    }));

    const { result } = renderHook(() => useWorkflowWidgetResults(['wf-a1', 'wf-b1'], { polling: false }), { wrapper });

    await waitFor(() => expect(result.current.data).toBeDefined());
    expect(urls).toEqual(['?workflowIds=wf-a1&workflowIds=wf-b1']);
    expect(result.current.data?.map((entry) => entry.workflowId)).toEqual(['wf-a1', 'wf-b1']);
    expect(result.current.data?.[0]?.result?.items).toEqual(['ok']);
    expect(result.current.data?.[1]).toMatchObject({ resultState: 'INVALID', result: null });
  });

  it('asks nothing without ids', async () => {
    let called = 0;
    server.use(http.get('/api/v1/dashboard/workflow-results', () => { called += 1; return HttpResponse.json([]); }));

    const { result } = renderHook(() => useWorkflowWidgetResults([], { polling: false }), { wrapper });

    await waitFor(() => expect(result.current.fetchStatus).toBe('idle'));
    expect(called).toBe(0);
    expect(result.current.data).toBeUndefined();
  });

  it('survives an answer that is not an array', async () => {
    server.use(http.get('/api/v1/dashboard/workflow-results', () => HttpResponse.json({ nope: true })));

    const { result } = renderHook(() => useWorkflowWidgetResults(['wf-a1'], { polling: false }), { wrapper });

    await waitFor(() => expect(result.current.data).toEqual([]));
  });

  it('treats different orders of the same ids as one query and refetches nothing', async () => {
    let calls = 0;
    server.use(http.get('/api/v1/dashboard/workflow-results', () => {
      calls += 1;
      return HttpResponse.json([ENTRY, { ...ENTRY, workflowId: 'wf-b1' }]);
    }));

    // A QueryClient created inside the wrapper component would be recreated on every rerender
    // (losing the cache); hoisting it here keeps the same client across the reorder below.
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    function stableWrapper({ children }: { children: ReactNode }) {
      return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    }

    const { result, rerender } = renderHook(
      ({ ids }: { ids: string[] }) => useWorkflowWidgetResults(ids, { polling: false }),
      { wrapper: stableWrapper, initialProps: { ids: ['wf-a1', 'wf-b1'] } },
    );

    await waitFor(() => expect(result.current.data).toBeDefined());
    expect(calls).toBe(1);

    rerender({ ids: ['wf-b1', 'wf-a1'] });

    expect(result.current.data?.map((entry) => entry.workflowId)).toEqual(['wf-a1', 'wf-b1']);
    expect(calls).toBe(1);
  });
});
