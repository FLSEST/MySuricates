import { failOnUnhandledRequest } from '../../test/msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useI18nStore } from '../../i18n';
import { en } from '../../i18n/en';
import { useToastStore } from '../../stores/toastStore';
import type { WorkflowFactoryResult } from '../../types';
import { useFactoryForm } from '../useFactoryForm';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

let requests: Array<{ path: string; body: Record<string, unknown> }>;
let runResult: Partial<WorkflowFactoryResult>;
/** The pipeline answers once this settles — lets a test look at the running state. */
let runGate: Promise<void>;
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  useToastStore.setState({ toasts: [] });
  requests = [];
  runResult = { status: 'VERIFIED' };
  runGate = Promise.resolve();
  server.use(
    http.post('/api/v1/factory/generate', async ({ request }) => {
      requests.push({ path: 'generate', body: (await request.json()) as Record<string, unknown> });
      return HttpResponse.json({ id: 'wf-1', name: 'Review Pipeline' });
    }),
    http.post('/api/v1/factory/run', async ({ request }) => {
      requests.push({ path: 'run', body: (await request.json()) as Record<string, unknown> });
      await runGate;
      return HttpResponse.json({
        workflowId: 'wf-2', workflowName: 'Tested Pipeline', status: 'VERIFIED', testRunId: 'r1', testStatus: 'PASSED',
        testError: null, promotedAsTemplate: false, durationMs: 1200, retryErrors: [], ...runResult,
      });
    }),
  );
});

function setup(testHealthy = true) {
  const onResult = vi.fn();
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client: qc }, children);
  const hook = renderHook(({ healthy }) => useFactoryForm({ testHealthy: healthy, onResult }), {
    wrapper, initialProps: { healthy: testHealthy },
  });
  return { ...hook, onResult };
}

const toasts = () => useToastStore.getState().toasts.map((t) => `${t.type}: ${t.message}`);

describe('useFactoryForm', () => {
  it('requires a topic and a project before generating', () => {
    const { result, onResult } = setup();
    act(() => result.current.create());
    act(() => result.current.setTopic('Code review'));
    act(() => result.current.create());
    expect(toasts()).toEqual([`error: ${en.factory.topicRequired}`, `error: ${en.factory.projectRequired}`]);
    expect(onResult).not.toHaveBeenCalled();
    expect(requests).toEqual([]);
  });

  it('generates with only the filled fields and reports a created result', async () => {
    const { result, onResult } = setup();
    act(() => {
      result.current.setTopic('Code review');
      result.current.setProjectId('p1');
      result.current.setTestInputs('{ "path": "/tmp" }');
    });
    act(() => result.current.create());

    await waitFor(() => expect(onResult).toHaveBeenCalled());
    expect(requests).toEqual([{ path: 'generate', body: { topic: 'Code review', projectId: 'p1', testInputs: { path: '/tmp' } } }]);
    expect(onResult).toHaveBeenCalledWith({
      workflowId: 'wf-1', workflowName: 'Review Pipeline', status: 'CREATED', testRunId: null, testStatus: null,
      testError: null, promotedAsTemplate: false, durationMs: 0, retryErrors: [],
    });
    expect(toasts()).toContain('success: Workflow “Review Pipeline” created');
    expect(result.current.isRunning).toBe(false);
  });

  it('sends invalid test inputs as an empty object and says so', async () => {
    const { result, onResult } = setup();
    act(() => {
      result.current.setTopic('Code review');
      result.current.setProjectId('p1');
      result.current.setTestInputs('{ not json');
    });
    act(() => result.current.create());
    await waitFor(() => expect(onResult).toHaveBeenCalled());
    expect(requests[0].body.testInputs).toEqual({});
    expect(toasts()[0]).toBe('error: Invalid JSON in test inputs');
  });

  it('tests only with the test instance up and passes the pipeline options', async () => {
    const { result, onResult, rerender } = setup(false);
    act(() => {
      result.current.setTopic('Code review');
      result.current.setProjectId('p1');
      result.current.setProvider('CODEX');
      result.current.setAutoFix(true);
      result.current.setMaxRetries(4);
    });
    act(() => result.current.createAndTest());
    expect(toasts()).toEqual([`error: ${en.factory.testOffline}`]);

    let release!: () => void;
    runGate = new Promise((resolve) => { release = resolve; });
    rerender({ healthy: true });
    act(() => result.current.createAndTest());
    await waitFor(() => expect(result.current.isTesting).toBe(true));
    expect(result.current.isRunning).toBe(true);
    expect(result.current.isGenerating).toBe(false);
    release();
    await waitFor(() => expect(onResult).toHaveBeenCalledWith(expect.objectContaining({ workflowId: 'wf-2', status: 'VERIFIED' })));
    expect(requests).toEqual([{ path: 'run', body: {
      topic: 'Code review', projectId: 'p1', testInputs: {}, autoPromote: false, autoFix: true, maxRetries: 4, provider: 'CODEX',
    } }]);
    expect(toasts()).toContain('success: Workflow “Tested Pipeline” verified!');
  });

  it('reports a failed pipeline run with its error', async () => {
    runResult = { status: 'FAILED', testError: 'step 2 timed out' };
    const { result, onResult } = setup();
    act(() => {
      result.current.setTopic('Code review');
      result.current.setProjectId('p1');
    });
    act(() => result.current.createAndTest());
    await waitFor(() => expect(onResult).toHaveBeenCalledWith(expect.objectContaining({ status: 'FAILED' })));
    expect(toasts()).toContain('error: Workflow failed: step 2 timed out');
  });

  it('speaks the UI language and passes the server error on (38a32469)', async () => {
    useI18nStore.setState({ locale: 'de' });
    const { result, onResult } = setup();
    act(() => {
      result.current.setTopic('Code review');
      result.current.setProjectId('p1');
      result.current.setTestInputs('{ not json');
    });
    act(() => result.current.create());
    await waitFor(() => expect(onResult).toHaveBeenCalled());
    expect(toasts()).toEqual([
      'error: Ungültiges JSON in den Test-Eingaben',
      'success: Workflow „Review Pipeline“ erstellt',
    ]);

    useToastStore.setState({ toasts: [] });
    server.use(http.post('/api/v1/factory/run', () => HttpResponse.json({ error: 'Test instance busy' }, { status: 503 })));
    act(() => result.current.setTestInputs(''));
    act(() => result.current.createAndTest());
    await waitFor(() => expect(toasts()).toEqual(['error: Factory-Pipeline fehlgeschlagen: Test instance busy']));
  });
});
