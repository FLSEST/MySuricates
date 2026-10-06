import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import type { WorkflowRun } from '../../types';
import type { LiveLogSource } from '../../types/websocket';
import { WorkflowExecution } from '../WorkflowExecution';

let liveLogSource: LiveLogSource = 'pending';

// No socket in jsdom: only the source of the replayed log varies.
vi.mock('../../hooks/useWebSocket', () => ({
  useExecutionWebSocket: () => ({
    logs: [], connected: false, pendingApproval: null, pendingHumanInput: null,
    agenticStates: new Map(), forEachStates: new Map(), liveLogSource,
  }),
}));

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

const finishedRun: WorkflowRun = {
  id: 'run-1', workflowId: 'wf-1', workflowName: 'Build', projectName: null, projectPath: null, status: 'COMPLETED',
  steps: [], startedAt: '2026-10-06T06:00:00Z', completedAt: '2026-10-06T06:01:00Z', durationMs: 60_000,
  userRating: null, error: null, metadata: {}, triggerSource: 'MANUAL', chainRunId: null, sealed: false,
};

function renderRun(run: WorkflowRun) {
  server.use(
    http.get('/api/v1/workflows/:id', () => HttpResponse.json({ id: 'wf-1', name: 'Build', blocks: [] })),
    http.get('/api/v1/workflows/:id/runs/:runId', () => HttpResponse.json(run)),
    http.get('/api/v1/executions/:runId/approval', () => HttpResponse.json({ pending: false })),
  );
  renderWithProviders(
    <Routes><Route path="/workflows/:id/run/:runId" element={<WorkflowExecution />} /></Routes>,
    { route: '/workflows/wf-1/run/run-1' },
  );
}

// bbbfcbf8: the log card says whether the replayed log is the stored one or none was kept
describe('WorkflowExecution – kept live log', () => {
  it('marks a log served from the database', async () => {
    liveLogSource = 'stored';
    renderRun(finishedRun);

    expect(await screen.findByText('Stored log')).toBeInTheDocument();
  });

  it('says so when a finished run kept no log, instead of waiting for lines', async () => {
    liveLogSource = 'none';
    renderRun(finishedRun);

    expect(await screen.findByText('No log kept')).toBeInTheDocument();
    expect(screen.getByText(/No live log kept/)).toBeInTheDocument();
    expect(screen.queryByText('Waiting for logs...')).not.toBeInTheDocument();
  });

  it('keeps waiting while the run is still going', async () => {
    liveLogSource = 'none';
    renderRun({ ...finishedRun, status: 'RUNNING', completedAt: null, durationMs: null });

    expect(await screen.findByText('Waiting for logs...')).toBeInTheDocument();
    expect(screen.queryByText('No log kept')).not.toBeInTheDocument();
  });
});
