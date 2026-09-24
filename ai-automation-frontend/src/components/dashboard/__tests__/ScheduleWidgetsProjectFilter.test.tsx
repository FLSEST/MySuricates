import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { ActiveProjectsStat, PlannedThisWeekStat, PlannedTodayStat } from '../StatWidgets';
import { UpcomingSchedulesCard } from '../UpcomingSchedulesCard';
import { DashboardDataContext, type DashboardDataValue } from '../dashboardDataContext';
import type { DashboardViewFilter } from '../dashboardLayout';

let todaySearches: string[] = [];
let weekSearches: string[] = [];

const run = (id: string, name: string) => ({
  scheduleId: id, workflowName: name, projectName: null, chainId: null, chainName: null,
  targetType: 'WORKFLOW', scheduledAt: '2099-01-01T06:00:00Z',
});

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  todaySearches = [];
  weekSearches = [];
  useI18nStore.setState({ locale: 'en' });
  server.use(
    http.get('/api/v1/schedules/remaining-today', ({ request }) => {
      const projectId = new URL(request.url).searchParams.get('projectId');
      todaySearches.push(projectId ?? '');
      // p2 has one schedule, the whole instance three — the count visibly changes on a switch.
      return HttpResponse.json(projectId === 'p2'
        ? { count: 1, schedules: [run('s2', 'Only p2')], pausedUntil: null }
        : { count: 3, schedules: [run('s1', 'Everywhere')], pausedUntil: null });
    }),
    http.get('/api/v1/schedules/remaining-this-week', ({ request }) => {
      const projectId = new URL(request.url).searchParams.get('projectId');
      weekSearches.push(projectId ?? '');
      return HttpResponse.json({ count: projectId === 'p2' ? 4 : 11, schedules: [], pausedUntil: null });
    }),
  );
});

function inView(ui: React.ReactElement, filter: DashboardViewFilter) {
  const value: DashboardDataValue = {
    data: undefined, isLoading: false, error: null, refetch: () => {},
    filter, days: 30, isPlaceholderData: false, dataDays: 30,
  };
  return <DashboardDataContext.Provider value={value}>{ui}</DashboardDataContext.Provider>;
}

/** The schedule widgets follow the view's project filter like the todo widgets (TODO 4e1c9ad4). */
describe('schedule widgets follow the dashboard project filter', () => {
  it('the planned tiles ask for the selected project and refetch on a switch', async () => {
    const tiles = <><PlannedTodayStat /><PlannedThisWeekStat /></>;
    const { rerender } = renderWithProviders(inView(tiles, {}));
    expect(await screen.findByText('3')).toBeInTheDocument();
    expect(await screen.findByText('11')).toBeInTheDocument();

    rerender(inView(tiles, { projectId: 'p2' }));
    expect(await screen.findByText('1')).toBeInTheDocument();
    expect(await screen.findByText('4')).toBeInTheDocument();
    expect(todaySearches).toEqual(['', 'p2']);
    expect(weekSearches).toEqual(['', 'p2']);
  });

  it('UpcomingSchedulesCard lists the selected project’s schedules after a switch', async () => {
    const { rerender } = renderWithProviders(inView(<UpcomingSchedulesCard />, {}));
    expect(await screen.findByText('Everywhere')).toBeInTheDocument();

    rerender(inView(<UpcomingSchedulesCard />, { projectId: 'p2' }));
    expect(await screen.findByText('Only p2')).toBeInTheDocument();
    await waitFor(() => expect(weekSearches).toContain('p2'));
  });

  it('Active Projects keeps counting every project and says so under a project filter', async () => {
    const { rerender } = renderWithProviders(inView(<ActiveProjectsStat />, {}));
    expect(await screen.findByText('Active Projects')).toBeInTheDocument();
    expect(screen.queryByText('all projects')).not.toBeInTheDocument();

    rerender(inView(<ActiveProjectsStat />, { projectId: 'p2' }));
    expect(await screen.findByText('all projects')).toBeInTheDocument();
  });
});
