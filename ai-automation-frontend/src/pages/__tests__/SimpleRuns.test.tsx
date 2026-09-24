import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import type { WorkflowRunListItem } from '../../types';
import { SimpleRuns } from '../SimpleRuns';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

function failedRun(id: string, name: string, startedAt: string): WorkflowRunListItem {
  return {
    id,
    workflowId: `wf-${id}`,
    workflowName: name,
    projectName: null,
    projectPath: null,
    status: 'FAILED',
    startedAt,
    completedAt: startedAt,
    durationMs: 1200,
    userRating: null,
    error: { message: 'boom' },
    metadata: {},
    stepCount: 1,
    lastStepStatus: 'FAILED',
    lastStepPreview: '',
  };
}

describe('SimpleRuns retry', () => {
  it('reruns the failed run with its original context via /executions/{id}/retry', async () => {
    const calls: string[] = [];
    server.use(
      http.get('/api/v1/executions', () => HttpResponse.json({
        content: [
          failedRun('run-1', 'Report A', '2026-09-24T10:00:00Z'),
          failedRun('run-2', 'Report B', '2026-09-24T09:00:00Z'),
        ],
        totalElements: 2, totalPages: 1, number: 0, size: 50,
      })),
      http.post('/api/v1/executions/:id/retry', ({ params }) => {
        calls.push(`retry:${params.id}`);
        return HttpResponse.json({ id: 'run-new', workflowId: 'wf-run-1', status: 'RUNNING', steps: [] });
      }),
      http.post('/api/v1/workflows/:id/execute', ({ params }) => {
        calls.push(`execute:${params.id}`);
        return HttpResponse.json({ id: 'run-new', workflowId: String(params.id), status: 'RUNNING', steps: [] });
      }),
    );

    renderWithProviders(<SimpleRuns />);
    const buttons = await screen.findAllByRole('button', { name: /try again/i });
    fireEvent.click(buttons[0]);

    await waitFor(() => expect(calls).toEqual(['retry:run-1']));
  });
});
