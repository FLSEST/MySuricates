import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useCalendarAggregated } from '../useCalendarAggregated';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => localStorage.clear());

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

const run = (id: string) => ({ id, workflowId: 'w1', workflowName: 'Nightly', status: 'COMPLETED', startedAt: '2026-09-20T06:00:00Z' });
const FROM = '2026-09-01T00:00:00Z';
const TO = '2026-09-30T23:59:59Z';

// fbd56d63: the hook passes on which source was cut, next to the merged events
describe('useCalendarAggregated', () => {
  it('lists the runs and names the cut of a capped runs source', async () => {
    server.use(http.get('/api/v1/schedules/calendar', () =>
      HttpResponse.json({ upcoming: [], pastRuns: [run('r1'), run('r2')], pastRunsTotal: 1200 })));

    const { result } = renderHook(() => useCalendarAggregated(FROM, TO, new Set(['runs'])), { wrapper });

    await waitFor(() => expect(result.current.events).toHaveLength(2));
    expect(result.current.limits).toEqual([{ sourceId: 'runs', label: 'Runs', shown: 2, total: 1200 }]);
  });

  it('names no cut for a complete answer or a disabled source', async () => {
    server.use(http.get('/api/v1/schedules/calendar', () =>
      HttpResponse.json({ upcoming: [], pastRuns: [run('r1')], pastRunsTotal: 1 })));

    const { result } = renderHook(() => useCalendarAggregated(FROM, TO, new Set(['runs'])), { wrapper });
    await waitFor(() => expect(result.current.events).toHaveLength(1));
    expect(result.current.limits).toEqual([]);

    const off = renderHook(() => useCalendarAggregated(FROM, TO, new Set()), { wrapper });
    expect(off.result.current.limits).toEqual([]);
    expect(off.result.current.events).toEqual([]);
  });
});
