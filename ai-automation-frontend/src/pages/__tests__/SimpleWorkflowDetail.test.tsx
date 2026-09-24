import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { Routes, Route } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import type { WorkflowRunListItem } from '../../types';
import { SimpleWorkflowDetail } from '../SimpleWorkflowDetail';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

function listRun(id: string, status: WorkflowRunListItem['status'], metadata: Record<string, unknown>): WorkflowRunListItem {
  return {
    id, workflowId: 'wf-1', workflowName: 'Weekly report', projectName: null, projectPath: null, status,
    startedAt: '2026-09-24T08:00:00Z', completedAt: '2026-09-24T08:01:00Z', durationMs: 60_000,
    userRating: null, error: null, metadata, stepCount: 2, lastStepStatus: 'COMPLETED', lastStepPreview: '',
  };
}

function serve(runs: WorkflowRunListItem[]) {
  server.use(
    http.get('/api/v1/workflows/:id', () => HttpResponse.json({
      id: 'wf-1', name: 'Weekly report', projectName: 'Home', blocks: [], defaultContext: {},
    })),
    http.get('/api/v1/workflows/:id/runs', () => HttpResponse.json({
      content: runs, totalElements: runs.length, totalPages: 1, number: 0, size: 50,
    })),
  );
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/workflows/:id" element={<SimpleWorkflowDetail />} />
    </Routes>,
    { route: '/workflows/wf-1' },
  );
}

const REPORT = { title: 'Sales this week', status: 'YELLOW', kpis: [], items: ['Tuesday was slow'] };

describe('SimpleWorkflowDetail latest result', () => {
  it('shows the report of the newest successful run', async () => {
    serve([
      listRun('run-3', 'FAILED', {}),
      listRun('run-2', 'COMPLETED', { dashboard_widget: REPORT }),
      listRun('run-1', 'COMPLETED', { dashboard_widget: { ...REPORT, title: 'Older report' } }),
    ]);
    renderPage();

    expect(await screen.findByText('Sales this week')).toBeInTheDocument();
    expect(screen.getByText('Latest result')).toBeInTheDocument();
    expect(screen.getByText('With remarks')).toBeInTheDocument();
    expect(screen.queryByText('Older report')).not.toBeInTheDocument();
  });

  it('shows no stale report when the newest successful run delivered none', async () => {
    serve([
      listRun('run-2', 'COMPLETED', {}),
      listRun('run-1', 'COMPLETED', { dashboard_widget: REPORT }),
    ]);
    renderPage();

    expect(await screen.findByText('Weekly report')).toBeInTheDocument();
    expect(await screen.findByText('Recent Runs (2)')).toBeInTheDocument();
    expect(screen.queryByText('Latest result')).not.toBeInTheDocument();
    expect(screen.queryByText('Sales this week')).not.toBeInTheDocument();
  });
});
