import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { Route, Routes, useNavigate } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import type { WorkflowRun } from '../../types';
import { WorkflowExecution } from '../WorkflowExecution';

// No socket in jsdom: the page only needs empty live data.
vi.mock('../../hooks/useWebSocket', () => ({
  useExecutionWebSocket: () => ({
    logs: [], connected: false, pendingApproval: null, pendingHumanInput: null,
    agenticStates: new Map(), forEachStates: new Map(),
  }),
}));

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

const NOTES = 'Add notes about this run...';

function run(id: string, notes: string): WorkflowRun {
  return {
    id, workflowId: 'wf-1', workflowName: 'Deploy', projectName: null, projectPath: null, status: 'COMPLETED',
    steps: [], startedAt: '2026-09-24T08:00:00Z', completedAt: '2026-09-24T08:01:00Z', durationMs: 60_000,
    userRating: null, error: null, metadata: notes ? { notes } : {},
  };
}

function serve(saved: unknown[]) {
  server.use(
    http.get('/api/v1/workflows/:id', () => HttpResponse.json({ id: 'wf-1', name: 'Deploy', blocks: [] })),
    http.get('/api/v1/workflows/:id/runs/:runId', ({ params }) =>
      HttpResponse.json(run(String(params.runId), params.runId === 'run-2' ? 'notes of run 2' : ''))),
    http.get('/api/v1/executions/:runId/approval', () => HttpResponse.json({ pending: false })),
    http.patch('/api/v1/executions/:runId/notes', async ({ request, params }) => {
      saved.push({ runId: params.runId, ...(await request.json() as object) });
      return HttpResponse.json({});
    }),
  );
}

/** Stands in for "Run again": the new run opens on the same page element. */
function OpenRun2() {
  const navigate = useNavigate();
  return <button type="button" onClick={() => navigate('/workflows/wf-1/run/run-2')}>open run 2</button>;
}

describe('WorkflowExecution notes', () => {
  it('starts the notes of the next run without the draft of the previous one', async () => {
    const saved: unknown[] = [];
    serve(saved);
    renderWithProviders(
      <Routes>
        <Route path="/workflows/:id/run/:runId" element={<><WorkflowExecution /><OpenRun2 /></>} />
      </Routes>,
      { route: '/workflows/wf-1/run/run-1' },
    );

    fireEvent.change(await screen.findByPlaceholderText(NOTES), { target: { value: 'draft for run 1' } });
    fireEvent.click(screen.getByRole('button', { name: 'open run 2' }));

    await waitFor(() => expect(screen.getByPlaceholderText(NOTES)).toHaveValue('notes of run 2'));
    fireEvent.blur(screen.getByPlaceholderText(NOTES));
    await new Promise((r) => setTimeout(r, 50));
    expect(saved).toEqual([]);
  });

  it('keeps the draft of the same run when reloading it fails once', async () => {
    const saved: unknown[] = [];
    serve(saved);
    const { queryClient } = renderWithProviders(
      <Routes><Route path="/workflows/:id/run/:runId" element={<WorkflowExecution />} /></Routes>,
      { route: '/workflows/wf-1/run/run-1' },
    );
    fireEvent.change(await screen.findByPlaceholderText(NOTES), { target: { value: 'keep me' } });

    server.use(http.get('/api/v1/workflows/:id/runs/:runId', () => HttpResponse.json({}, { status: 500 })));
    await queryClient.invalidateQueries({ queryKey: ['workflows', 'wf-1', 'runs', 'detail', 'run-1'] });
    const retry = await screen.findByRole('button', { name: 'Retry' });

    serve(saved);
    fireEvent.click(retry);
    expect(await screen.findByPlaceholderText(NOTES)).toHaveValue('keep me');
    expect(saved).toEqual([]);
  });
});

