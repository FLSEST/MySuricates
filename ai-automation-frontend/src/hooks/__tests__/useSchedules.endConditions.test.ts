import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { getTranslations } from '../../i18n';
import { useToggleSchedule, useUpdateSchedule } from '../useSchedules';

/**
 * Since 1e801952 the backend refuses a schedule without a run left (end date before its next run,
 * maxRuns used up) with a 400 and a messageKey. The toast shows it in the UI language; an older
 * backend that still accepts the request keeps working as before.
 */

const mocks = vi.hoisted(() => ({ toastSuccess: vi.fn(), toastError: vi.fn() }));

vi.mock('../useToast', () => ({
  useToast: () => ({ success: mocks.toastSuccess, error: mocks.toastError }),
}));

const apiBase = '/api/v1';

function createWrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
}

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => {
  server.resetHandlers();
  vi.clearAllMocks();
});
afterAll(() => server.close());

describe('schedule end conditions', () => {
  it('shows the translated refusal when enabling a schedule whose runs are used up', async () => {
    server.use(
      http.post(`${apiBase}/schedules/sc1/enable`, () =>
        HttpResponse.json({
          error: 'The schedule has already started 5 time(s), its maximum is 5 — raise the maximum runs, or set them to 0 for no limit',
          code: 'INVALID_ARGUMENT',
          messageKey: 'SCHEDULE_MAX_RUNS_REACHED',
          params: { runCount: '5', maxRuns: '5' },
        }, { status: 400 }),
      ),
    );
    const t = getTranslations();
    const { result } = renderHook(() => useToggleSchedule(), { wrapper: createWrapper() });

    await act(async () => {
      result.current.mutate({ id: 'sc1', enable: true });
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const text = t.errors.messages.SCHEDULE_MAX_RUNS_REACHED.replace('{runCount}', '5').replace('{maxRuns}', '5');
    expect(mocks.toastError).toHaveBeenCalledWith(`${t.toast.scheduleToggleFailed}: ${text}`);
  });

  it('shows the translated refusal when an update moves the end date before the next run', async () => {
    server.use(
      http.put(`${apiBase}/schedules/sc1`, () =>
        HttpResponse.json({
          error: "The schedule's next run (2026-10-04T08:00:00Z) is not before its end date (2026-10-04T07:00:00Z) — it would never start",
          code: 'INVALID_ARGUMENT',
          messageKey: 'SCHEDULE_NEXT_RUN_AFTER_END_DATE',
          params: { nextRun: '2026-10-04T08:00:00Z', endDate: '2026-10-04T07:00:00Z' },
        }, { status: 400 }),
      ),
    );
    const t = getTranslations();
    const { result } = renderHook(() => useUpdateSchedule(), { wrapper: createWrapper() });

    await act(async () => {
      result.current.mutate({ id: 'sc1', data: { endDate: '2026-10-04T07:00:00Z' } });
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const text = t.errors.messages.SCHEDULE_NEXT_RUN_AFTER_END_DATE
      .replace('{nextRun}', '2026-10-04T08:00:00Z').replace('{endDate}', '2026-10-04T07:00:00Z');
    expect(mocks.toastError).toHaveBeenCalledWith(`${t.toast.scheduleUpdateFailed}: ${text}`);
  });

  it('keeps working with an older backend that accepts the request', async () => {
    server.use(
      http.post(`${apiBase}/schedules/sc1/enable`, () => HttpResponse.json({ id: 'sc1', enabled: true })),
    );
    const { result } = renderHook(() => useToggleSchedule(), { wrapper: createWrapper() });

    await act(async () => {
      result.current.mutate({ id: 'sc1', enable: true });
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mocks.toastSuccess).toHaveBeenCalledWith(getTranslations().toast.scheduleToggled);
    expect(mocks.toastError).not.toHaveBeenCalled();
  });
});
