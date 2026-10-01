import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { screen, fireEvent, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { modulesFixture } from '../../../test/modules';
import { useI18nStore } from '../../../i18n';
import { RunHeader } from '../RunHeader';
import type { Workflow, WorkflowRun } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => {
  server.resetHandlers();
  vi.restoreAllMocks();
});
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

const workflow = { id: 'wf-1', name: 'Deploy', projectId: 'p1' } as Workflow;

function run(status: string, extra: Partial<WorkflowRun> = {}): WorkflowRun {
  return {
    id: 'run-1', workflowId: 'wf-1', workflowName: 'Deploy', status, startedAt: '2026-09-14T10:00:00Z',
    steps: [], metadata: {}, error: null, projectPath: null, ...extra,
  } as unknown as WorkflowRun;
}

/** Records the path of every run action and every created TODO. */
function recordActions(calls: string[], todos: unknown[] = []) {
  server.use(
    http.post('/api/v1/executions/:runId/:action', ({ params }) => {
      calls.push(`${params.action} ${params.runId}`);
      return HttpResponse.json({});
    }),
    http.delete('/api/v1/executions/:runId', ({ params }) => {
      calls.push(`delete ${params.runId}`);
      return new HttpResponse(null, { status: 204 });
    }),
    http.post('/api/v1/todos', async ({ request }) => {
      todos.push(await request.json());
      return HttpResponse.json({ id: 't1' });
    }),
  );
}

function renderHeader(r: WorkflowRun, isRunning = false, connected = false) {
  return renderWithProviders(
    <Routes>
      <Route path="/workflows/:id/run/:runId" element={
        <RunHeader run={r} workflow={workflow} workflowId="wf-1" connected={connected} isRunning={isRunning} />
      } />
      <Route path="/workflows/:id" element={<div>workflow page</div>} />
    </Routes>,
    { route: '/workflows/wf-1/run/run-1' },
  );
}

describe('RunHeader', () => {
  it('pauses and cancels a running run and marks the live connection', async () => {
    const calls: string[] = [];
    recordActions(calls);
    renderHeader(run('RUNNING'), true, true);

    expect(screen.getByText('Live')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete run' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(calls).toEqual(['pause run-1', 'cancel run-1']));
  });

  it('resumes a paused run', async () => {
    const calls: string[] = [];
    recordActions(calls);
    renderHeader(run('PAUSED'), true);

    expect(screen.queryByRole('button', { name: 'Pause' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Resume' }));
    await waitFor(() => expect(calls).toEqual(['resume run-1']));
  });

  it('resumes an interrupted run from the last completed step', async () => {
    const calls: string[] = [];
    recordActions(calls);
    renderHeader(run('INTERRUPTED', { steps: [{ id: 's0', stepIndex: 0, status: 'COMPLETED' }] as WorkflowRun['steps'] }));

    fireEvent.click(screen.getByRole('button', { name: 'Resume from last step' }));
    await waitFor(() => expect(calls).toEqual(['resume-from-step run-1']));
  });

  it('creates the TODO of a failed run in the app language', async () => {
    useI18nStore.setState({ locale: 'de' });
    const todos: unknown[] = [];
    recordActions([], todos);
    renderHeader(run('FAILED', {
      error: { message: 'exit 1' },
      steps: [{ id: 's0', stepIndex: 0, status: 'FAILED', blockType: 'shell', error: null }] as unknown as WorkflowRun['steps'],
    }));

    fireEvent.click(screen.getByRole('button', { name: 'Todo erstellen' }));
    await waitFor(() => expect(todos).toHaveLength(1));
    expect(todos[0]).toMatchObject({
      title: 'Deploy: Run fehlgeschlagen', priority: 'HIGH', tags: ['failed-run'], projectId: 'p1',
      source: 'WORKFLOW', workflowRunId: 'run-1',
    });
    expect((todos[0] as { description: string }).description).toContain('**Fehlgeschlagener Step:** Step 1 (shell)');
  });

  // 23635ef6: the app's own dialog instead of window.confirm
  it('deletes a finished run after confirmation and goes to the workflow', async () => {
    const calls: string[] = [];
    recordActions(calls);
    renderHeader(run('COMPLETED'));

    const del = await screen.findByRole('button', { name: 'Delete run' });
    fireEvent.click(del);
    const dialog = screen.getByRole('dialog', { name: 'Delete run' });
    expect(within(dialog).getByText(/Permanently delete this run\?/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(calls).toEqual([]);

    fireEvent.click(del);
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Delete run' })).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('workflow page')).toBeInTheDocument();
    expect(calls).toEqual(['delete run-1']);
  });

  it('offers no deletion when the instance forbids it', async () => {
    server.use(http.get('/api/v1/system/capabilities', () =>
      HttpResponse.json({ routes: [], blockTypes: [], ...modulesFixture('full', []), runDeletionAllowed: false })));
    renderHeader(run('COMPLETED'));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Delete run' })).not.toBeInTheDocument());
  });
});
