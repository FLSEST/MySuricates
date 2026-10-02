import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { formatDuration } from '../../../utils/formatTime';
import { DASHBOARD_FIXTURE } from '../../../test/dashboardPage';
import { DashboardDataProvider } from '../DashboardDataProvider';
import { RecentRunsCard } from '../RecentRunsCard';
import { FailingWorkflowsWidget } from '../DashboardDataWidgets';

/**
 * Since TODO 2ccb9e40 the backend sends the recent runs as slim rows; a backend that has not been
 * restarted yet still sends whole runs with their steps. Both widgets on the recent runs must show
 * the same with either answer.
 */
const SLIM_RUNS = [
  {
    id: 'run-1', workflowId: 'wf-1', workflowName: 'Nightly Build', projectName: 'AutoPilot',
    status: 'FAILED', startedAt: '2026-10-01T06:00:00Z', completedAt: '2026-10-01T06:00:05Z', durationMs: 5000,
  },
  {
    id: 'run-2', workflowId: 'wf-2', workflowName: 'Audit', projectName: null,
    status: 'COMPLETED', startedAt: '2026-10-01T05:00:00Z', completedAt: '2026-10-01T05:00:02Z', durationMs: 2000,
  },
];

const FULL_RUNS = SLIM_RUNS.map((run) => ({
  ...run,
  projectPath: '/tmp/project',
  steps: [{ id: `${run.id}-s0`, stepIndex: 0, status: 'COMPLETED', input: 'x'.repeat(2000), output: 'y'.repeat(2000) }],
  userRating: null,
  error: null,
  metadata: { gate_status: 'GREEN' },
  inputContext: { previous_report: 'z'.repeat(2000) },
}));

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

describe.each([
  ['slim rows (current backend)', SLIM_RUNS],
  ['whole runs with steps (backend before TODO 2ccb9e40)', FULL_RUNS],
])('recent runs from %s', (_label, recentRuns) => {
  beforeEach(() => {
    server.use(http.get('/api/v1/learning/dashboard', () => HttpResponse.json({ ...DASHBOARD_FIXTURE, recentRuns })));
  });

  it('lists name, project and duration of each run', async () => {
    renderWithProviders(
      <DashboardDataProvider filter={undefined}>
        <RecentRunsCard />
      </DashboardDataProvider>,
    );

    expect(await screen.findByText('Nightly Build')).toBeInTheDocument();
    expect(screen.getByText('AutoPilot')).toBeInTheDocument();
    expect(screen.getByText('Audit')).toBeInTheDocument();
    expect(screen.getByText(formatDuration(5000, 'en'))).toBeInTheDocument();
  });

  it('counts the failed workflow among the failing ones', async () => {
    renderWithProviders(
      <DashboardDataProvider filter={undefined}>
        <FailingWorkflowsWidget />
      </DashboardDataProvider>,
    );

    expect(await screen.findByText('Nightly Build')).toBeInTheDocument();
    expect(screen.getByText('100%')).toBeInTheDocument();
    expect(screen.queryByText('Audit')).not.toBeInTheDocument();
  });
});
