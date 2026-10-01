import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { schedulesAdapter } from '../schedulesAdapter';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const schedule = (over: Record<string, unknown>) => ({
  id: 'sc', workflowId: 'w1', workflowName: 'Nightly', projectName: null, chainId: null, chainName: null,
  targetType: 'WORKFLOW', cronExpression: '0 0 8 * * *', scheduleType: 'CRON', scheduledAt: null,
  delayExpression: null, description: '', inputContext: {}, enabled: true, nextRunAt: null, lastRunAt: null,
  createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z', pausedUntil: null, ...over,
});

/** The calendar places a schedule at the run that really comes (a776fd2a). */
describe('schedulesAdapter', () => {
  it('uses nextRunAt, and drops a one-off that an active pause lets expire', async () => {
    server.use(
      http.get('/api/v1/schedules/calendar', () => HttpResponse.json({
        upcoming: [
          schedule({ id: 'held-cron', nextRunAt: '2026-10-02T06:00:00Z', pausedUntil: '2026-10-01T22:00:00Z' }),
          schedule({ id: 'held-once', scheduleType: 'ONCE', scheduledAt: '2026-09-30T09:00:00Z',
            nextRunAt: null, pausedUntil: '2026-10-01T22:00:00Z' }),
          schedule({ id: 'plain-once', scheduleType: 'ONCE', scheduledAt: '2026-10-05T09:00:00Z', nextRunAt: null }),
        ],
        pastRuns: [],
      })),
    );

    const events = await schedulesAdapter.fetchEvents('2026-09-28T00:00:00Z', '2026-10-31T00:00:00Z');

    expect(events.map((e) => e.id)).toEqual(['sched-held-cron', 'sched-plain-once']);
    expect(events[0]!.start.toISOString()).toBe('2026-10-02T06:00:00.000Z');
    expect(events[1]!.start.toISOString()).toBe('2026-10-05T09:00:00.000Z');
  });
});
