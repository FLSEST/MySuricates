import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, beforeEach, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { modulesFixture } from '../../test/modules';
import { useDashboardReportAssist, useDashboardReportAssistAvailable } from '../useDashboardReportAssist';
import { useExecutionConfig, useProviderModels } from '../useSystem';
import { useModuleInfo } from '../useModules';
import { apiErrorMessage } from '../../api/client';
import { getTranslations, useI18nStore } from '../../i18n';

const BASE = '/api/v1';

/**
 * This file exists because the ONE new test in task 9 (DashboardReportAssist.test.tsx) mocks this
 * hook away entirely — the re-validation (parseWorkflowResult) and the three-switch gate this task
 * was built for are covered by nothing. Proof: relaxing the hook's guard to `if (!raw.proposal)`
 * leaves the rest of the suite green while letting a proposal with an unknown field or a
 * non-string runId through — exactly the cases below.
 */

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

const VALID_BODY = {
  runId: 'run-1',
  runCompletedAt: '2026-09-18T08:02:00Z',
  provider: 'CLAUDE',
  proposal: { status: 'GREEN', kpis: [], items: [], title: 'Nightly QA', summary: 'Alles gut.' },
};

describe('useDashboardReportAssist', () => {
  beforeEach(() => {
    useI18nStore.setState({ locale: 'en' });
  });

  it('accepts a proposal that matches the contract', async () => {
    server.use(http.post(`${BASE}/dashboard/report-assist`, () => HttpResponse.json(VALID_BODY)));
    const { result } = renderHook(() => useDashboardReportAssist(), { wrapper: createWrapper() });

    act(() => {
      result.current.mutate('wf-a1');
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.runId).toBe('run-1');
    expect(result.current.data?.proposal.title).toBe('Nightly QA');
  });

  it('rejects a proposal carrying a field outside the contract', async () => {
    server.use(http.post(`${BASE}/dashboard/report-assist`, () => HttpResponse.json({
      ...VALID_BODY,
      proposal: { ...VALID_BODY.proposal, mystery: 'not part of the contract' },
    })));
    const { result } = renderHook(() => useDashboardReportAssist(), { wrapper: createWrapper() });

    act(() => {
      result.current.mutate('wf-a1');
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
  });

  /**
   * The specific "did not match the contract" text must actually reach the toast. A bare
   * `throw new Error(...)` has no `.error` field, so `apiErrorMessage`'s `'error' in err` check
   * misses it and silently falls back to the generic "failed" text instead — this pins the thrown
   * value's shape against that regression.
   */
  it('rejects with a message apiErrorMessage can surface, not a bare Error', async () => {
    server.use(http.post(`${BASE}/dashboard/report-assist`, () => HttpResponse.json({
      ...VALID_BODY,
      proposal: { ...VALID_BODY.proposal, mystery: 'not part of the contract' },
    })));
    const { result } = renderHook(() => useDashboardReportAssist(), { wrapper: createWrapper() });

    act(() => {
      result.current.mutate('wf-a1');
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const invalid = getTranslations().workflows.dashboardReport.assist.invalid;
    const failed = getTranslations().workflows.dashboardReport.assist.failed;
    expect(apiErrorMessage(result.current.error, failed)).toBe(`${failed}: ${invalid}`);
  });

  it('rejects a proposal whose runId is not a string', async () => {
    server.use(http.post(`${BASE}/dashboard/report-assist`, () => HttpResponse.json({
      ...VALID_BODY, runId: 42,
    })));
    const { result } = renderHook(() => useDashboardReportAssist(), { wrapper: createWrapper() });

    act(() => {
      result.current.mutate('wf-a1');
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
  });

  it('rejects a proposal with an item over the 200-character cap', async () => {
    server.use(http.post(`${BASE}/dashboard/report-assist`, () => HttpResponse.json({
      ...VALID_BODY,
      proposal: { ...VALID_BODY.proposal, items: ['x'.repeat(201)] },
    })));
    const { result } = renderHook(() => useDashboardReportAssist(), { wrapper: createWrapper() });

    act(() => {
      result.current.mutate('wf-a1');
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
  });
});

/**
 * `useDashboardReportAssistAvailable` starts false (it depends on two queries that resolve
 * asynchronously) even in the "off" cases below, so waiting for `available === false` alone would
 * pass on a still-loading hook, not on a genuinely disabled switch. `useProbe` also waits for the
 * three underlying queries to actually settle, so `ready` only becomes true once the negative
 * result is real.
 */
function useProbe() {
  const available = useDashboardReportAssistAvailable();
  const claudeModels = useProviderModels('CLAUDE');
  const config = useExecutionConfig();
  const moduleInfo = useModuleInfo('dashboard-report');
  return { available, ready: claudeModels.isSuccess && config.isSuccess && moduleInfo !== undefined };
}

describe('useDashboardReportAssistAvailable', () => {
  it('is true once the module, the setting and a usable Claude CLI are all there', async () => {
    const { result } = renderHook(() => useProbe(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.ready).toBe(true));
    expect(result.current.available).toBe(true);
  });

  it('is false when the dashboard-report module is off', async () => {
    server.use(http.get(`${BASE}/system/capabilities`, () =>
      HttpResponse.json({ apiVersion: 'v1', routes: [], blockTypes: [], ...modulesFixture('full', ['dashboard-report']) })));
    const { result } = renderHook(() => useProbe(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.ready).toBe(true));
    expect(result.current.available).toBe(false);
  });

  it('is false when the runtime setting is off', async () => {
    server.use(http.get(`${BASE}/system/execution-config`, () =>
      HttpResponse.json({
        executionMaxConcurrent: 5, claudeMaxConcurrentCalls: 5, claudeReservedForInteractive: 1,
        claudeTimeoutSeconds: 300, claudeInterCallDelayMs: 0, chainWorkflowTimeoutMinutes: 30,
        assistantChatTimeoutSeconds: 240, activeRuns: 0, queuedRuns: 0, dashboardReportAssistEnabled: 0,
      })));
    const { result } = renderHook(() => useProbe(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.ready).toBe(true));
    expect(result.current.available).toBe(false);
  });

  /**
   * The host's answer, not the bean list: `/system/ai-providers` still lists CLAUDE on an instance
   * without the CLI (it names Spring beans), so the button hides on the model catalog's
   * `available`, which for CLAUDE says whether the `claude` program exists on this host.
   */
  it('is false when this host has no usable Claude CLI', async () => {
    server.use(http.get(`${BASE}/system/ai-providers/:id/models`, ({ params }) =>
      HttpResponse.json({
        providerId: String(params.id).toUpperCase(), available: false, source: 'static-aliases',
        fetchedAt: null, reason: 'claude is not installed on this host (not found on PATH)', models: [],
      })));
    const { result } = renderHook(() => useProbe(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.ready).toBe(true));
    expect(result.current.available).toBe(false);
  });
});
