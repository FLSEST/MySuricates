import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { ScheduleRunsPanel } from '../ScheduleRunsPanel';
import { CalendarView } from '../CalendarView';
import type { WorkflowRunListItem } from '../../../types';

const BASE = '/api/v1';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function run(id: string, startedAt: string): WorkflowRunListItem {
  return {
    id, workflowId: 'w1', workflowName: 'Nightly', projectName: 'Project', status: 'COMPLETED',
    startedAt, completedAt: null, durationMs: 5000, stepCount: 1, lastStepStatus: 'COMPLETED', lastStepPreview: null,
  } as unknown as WorkflowRunListItem;
}

const runs = [run('r2', new Date().toISOString()), run('r1', new Date().toISOString())];

// 86777fb4: the schedule's runs and the calendar come cut at a limit — "more" only when the backend says so
describe('ScheduleRunsPanel run limit', () => {
  it('names the newest and the total when the new backend cut the list', async () => {
    server.use(http.get(`${BASE}/schedules/:id/runs`, () =>
      HttpResponse.json({ workflowRuns: runs, chainRuns: [], workflowRunsTotal: 26000, chainRunsTotal: 0 })));
    renderWithProviders(<ScheduleRunsPanel scheduleId="s1" />);

    expect(await screen.findByTestId('schedule-runs-limited')).toHaveTextContent('Showing the newest 2 of 26,000 runs.');
    expect(screen.getByText(/Run History/)).toHaveTextContent('(26,000)');
  });

  it('says nothing more when the new backend sent every run', async () => {
    server.use(http.get(`${BASE}/schedules/:id/runs`, () =>
      HttpResponse.json({ workflowRuns: runs, chainRuns: [], workflowRunsTotal: 2, chainRunsTotal: 0 })));
    renderWithProviders(<ScheduleRunsPanel scheduleId="s1" />);

    expect(await screen.findByText(/Run History/)).toHaveTextContent('(2)');
    expect(screen.queryByTestId('schedule-runs-limited')).toBeNull();
  });

  it('counts the lists of an older backend without totals and says nothing more', async () => {
    server.use(http.get(`${BASE}/schedules/:id/runs`, () =>
      HttpResponse.json({ workflowRuns: runs, chainRuns: [] })));
    renderWithProviders(<ScheduleRunsPanel scheduleId="s1" />);

    expect(await screen.findByText(/Run History/)).toHaveTextContent('(2)');
    expect(screen.queryByTestId('schedule-runs-limited')).toBeNull();
  });
});

describe('CalendarView run limit', () => {
  it('says the month shows only the newest runs when the new backend cut them', async () => {
    server.use(http.get(`${BASE}/schedules/calendar`, () =>
      HttpResponse.json({ upcoming: [], pastRuns: runs, pastRunsTotal: 8640 })));
    renderWithProviders(<CalendarView />);

    expect(await screen.findByTestId('calendar-runs-limited'))
      .toHaveTextContent('The calendar shows the newest 2 of 8,640 runs this month');
  });

  it('says nothing for an older backend that sends every run without a total', async () => {
    server.use(http.get(`${BASE}/schedules/calendar`, () =>
      HttpResponse.json({ upcoming: [], pastRuns: runs })));
    renderWithProviders(<CalendarView />);

    // today's cell carries both runs: the answer is on screen
    expect(await screen.findByTitle('2 successful')).toBeInTheDocument();
    expect(screen.queryByTestId('calendar-runs-limited')).toBeNull();
  });

  it('says nothing when the new backend sent every run of the month', async () => {
    server.use(http.get(`${BASE}/schedules/calendar`, () =>
      HttpResponse.json({ upcoming: [], pastRuns: runs, pastRunsTotal: 2 })));
    renderWithProviders(<CalendarView />);

    expect(await screen.findByTitle('2 successful')).toBeInTheDocument();
    expect(screen.queryByTestId('calendar-runs-limited')).toBeNull();
  });
});
