import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
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

const chainEntry: WorkflowRun = {
  id: 'run-1', workflowId: 'wf-1', workflowName: 'Audit', projectName: null, projectPath: null, status: 'COMPLETED',
  steps: [], startedAt: '2026-10-03T06:00:00Z', completedAt: '2026-10-03T06:01:00Z', durationMs: 60_000,
  userRating: null, error: null, metadata: {}, triggerSource: 'SCHEDULE', chainRunId: 'chain-run-7', sealed: false,
};

function renderRun(run: unknown) {
  server.use(
    http.get('/api/v1/workflows/:id', () => HttpResponse.json({ id: 'wf-1', name: 'Audit', blocks: [] })),
    http.get('/api/v1/workflows/:id/runs/:runId', () => HttpResponse.json(run)),
    http.get('/api/v1/executions/:runId/approval', () => HttpResponse.json({ pending: false })),
  );
  renderWithProviders(
    <Routes><Route path="/workflows/:id/run/:runId" element={<WorkflowExecution />} /></Routes>,
    { route: '/workflows/wf-1/run/run-1' },
  );
}

// 3a35ced1: the run origin links the chain the run was an entry of
describe('WorkflowExecution – run origin', () => {
  it('links the chain when the backend names it', async () => {
    renderRun({ ...chainEntry, chainId: 'chain-3' });

    expect(await screen.findByTestId('run-chain')).toHaveAttribute('href', '/chains?chain=chain-3');
  });

  it('shows the origin as before when an older backend sends no chainId', async () => {
    renderRun(chainEntry);

    expect(await screen.findByTestId('run-provenance')).toBeInTheDocument();
    expect(screen.getByTestId('trigger-source')).toBeInTheDocument();
    expect(screen.queryByTestId('run-chain')).not.toBeInTheDocument();
  });
});
