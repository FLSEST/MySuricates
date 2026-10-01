import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useScheduleForm } from '../useScheduleForm';
import type { ScheduledWorkflow } from '../../types';

const BASE = '/api/v1';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

const schedule: ScheduledWorkflow = {
  id: 'sc1', workflowId: 'w1', workflowName: 'Nightly', projectName: null, chainId: null, chainName: null,
  targetType: 'WORKFLOW', cronExpression: '0 0 2 * * *', scheduleType: 'CRON', scheduledAt: null,
  delayExpression: null, description: '', inputContext: {}, enabled: true, nextRunAt: null, lastRunAt: null,
  createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z', conflictBehavior: 'SKIP',
};

describe('useScheduleForm', () => {
  it('sends a changed conflict behaviour when an existing schedule is saved (ade3fef8)', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.put(`${BASE}/schedules/:id`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ ...schedule, conflictBehavior: 'OVERLAP' });
      }),
    );
    const { result } = renderHook(() => useScheduleForm(), { wrapper: createWrapper() });

    act(() => result.current.handleEdit(schedule));
    act(() => result.current.setForm({ ...result.current.form, conflictBehavior: 'OVERLAP' }));
    act(() => result.current.handleSubmit());

    await waitFor(() => expect(body).toBeDefined());
    expect(body!.conflictBehavior).toBe('OVERLAP');
  });
});
