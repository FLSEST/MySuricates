import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { runsAdapter } from '../runsAdapter';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const run = (id: string, status = 'COMPLETED') => ({
  id, workflowId: 'w1', workflowName: 'Nightly', status, startedAt: '2026-09-20T06:00:00Z',
});

function answer(body: Record<string, unknown>) {
  server.use(http.get('/api/v1/schedules/calendar', () => HttpResponse.json({ upcoming: [], ...body })));
}

const fetchRuns = () => runsAdapter.fetchEvents('2026-09-01T00:00:00Z', '2026-09-30T23:59:59Z');

// fbd56d63: the backend cuts a range at 1000 past runs (86777fb4); the unified calendar said nothing about it.
describe('runsAdapter', () => {
  it('reports the cut when the backend names more runs than it sent', async () => {
    answer({ pastRuns: [run('r1'), run('r2', 'FAILED')], pastRunsTotal: 1500 });

    const result = await fetchRuns();

    expect(result.events.map((e) => [e.id, e.color])).toEqual([['run-r1', 'bg-success'], ['run-r2', 'bg-danger']]);
    expect(result.limited).toEqual({ shown: 2, total: 1500 });
  });

  it('reports no cut when all runs came, or an older backend sends no total', async () => {
    answer({ pastRuns: [run('r1')], pastRunsTotal: 1 });
    expect((await fetchRuns()).limited).toBeUndefined();

    answer({ pastRuns: [run('r1')] });
    const old = await fetchRuns();
    expect(old.limited).toBeUndefined();
    expect(old.events).toHaveLength(1);
  });
});
