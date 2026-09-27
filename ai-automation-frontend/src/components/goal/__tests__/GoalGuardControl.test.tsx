import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { modulesFixture } from '../../../test/modules';
import { useI18nStore } from '../../../i18n';
import { useGoal } from '../../../hooks/useGoals';
import { useToastStore } from '../../../stores/toastStore';
import { selectActiveView, useDashboardLayoutStore } from '../../../stores/dashboardLayoutStore';
import { useDashboardSyncStore } from '../../../stores/dashboardSyncStore';
import { createDefaultLayout } from '../../dashboard/dashboardLayout';
import { GoalGuardControl } from '../GoalGuardControl';
import type { GoalDetail, GoalGuard } from '../../../types';
// Switching on imports the dashboard registry lazily (useGoalGuard). In Vitest that import transforms the
// registry with all its widgets and charts, under load longer than a waitFor waits for the tile
// (b26bb8ff). Loaded here, the lazy import finds it ready and the test measures the flow, not the import.
import '../../dashboard/workflowResultTile';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useToastStore.setState({ toasts: [] });
  useDashboardLayoutStore.setState(createDefaultLayout());
  useDashboardSyncStore.setState({ baseVersionId: null, dirty: false });
});

const OFF: GoalGuard = { enabled: false, workflowId: null, scheduleId: null, nextRunAt: null };
const ON: GoalGuard = { enabled: true, workflowId: 'wf-guard', scheduleId: 's1', nextRunAt: '2026-09-25T06:00:00Z' };

function detail(guard: GoalGuard): GoalDetail {
  return {
    goal: {
      id: 'g1', title: 'Open-Source-Launch', description: null, status: 'ACTIVE', targetDate: null,
      createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
      progress: { total: 4, open: 4, inProgress: 0, backlog: 0, done: 0, openQuestions: 0, percentDone: 0 },
      guard,
    },
    todos: [],
    byProject: [
      { projectId: 'p1', projectName: 'AutoPilot', total: 1, done: 0 },
      { projectId: 'p2', projectName: 'Site', total: 3, done: 0 },
    ],
    projects: [],
    milestones: [],
  };
}

/** Das Ziel liefert den jeweils aktuellen Wächter; POST /guard schaltet ihn wie das Backend. */
function serveGoal(initial: GoalGuard) {
  const state = { guard: initial, sent: [] as unknown[], executed: [] as string[] };
  server.use(
    http.get('/api/v1/goals/g1', () => HttpResponse.json(detail(state.guard))),
    http.get('/api/v1/projects', () => HttpResponse.json([
      { id: 'p1', name: 'AutoPilot', type: 'CODE', path: '/tmp/a', status: 'STOPPED', container: null, workflowCount: 1 },
      { id: 'p2', name: 'Site', type: 'CODE', path: '/tmp/s', status: 'STOPPED', container: null, workflowCount: 1 },
    ])),
    http.post('/api/v1/goals/g1/guard', async ({ request }) => {
      const body = (await request.json()) as { enabled: boolean };
      state.sent.push(body);
      state.guard = body.enabled ? ON : { ...state.guard, enabled: false, nextRunAt: null };
      return HttpResponse.json(detail(state.guard).goal);
    }),
    http.post('/api/v1/workflows/:id/execute', ({ params }) => {
      state.executed.push(String(params.id));
      return HttpResponse.json({ id: 'run-1', workflowId: params.id, status: 'PENDING' });
    }),
    // The schedule of a guard that exists (its time can be changed at the goal page)
    http.get('/api/v1/schedules/s1', () => HttpResponse.json({
      id: 's1', workflowId: 'wf-guard', targetType: 'WORKFLOW', scheduleType: 'CRON', cronExpression: '0 0 8 * * *', enabled: true,
    })),
  );
  return state;
}

function Harness() {
  const { data } = useGoal('g1');
  return (
    <>
      {data && <GoalGuardControl goal={data.goal} byProject={data.byProject} />}
      <p>{data ? 'goal loaded' : 'loading'}</p>
    </>
  );
}

const resultTiles = () => (selectActiveView(useDashboardLayoutStore.getState())?.widgets ?? [])
  .filter((widget) => widget.type === 'workflow-result')
  .map((widget) => widget.config?.workflowId);
const toasts = () => useToastStore.getState().toasts.map((toast) => toast.message);

// The first switch-on loads the dashboard registry on demand (workflowResultTile): under the load of
// a full parallel suite that import alone passed the 5s default.
describe('GoalGuardControl', { timeout: 20_000 }, () => {
  it('asks for project and time on the first switch-on, then adds the tile to the dashboard', async () => {
    const state = serveGoal(OFF);
    renderWithProviders(<Harness />);

    fireEvent.click(await screen.findByRole('switch', { name: 'Guard' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Switch on the goal guard')).toBeInTheDocument();
    // vorbelegt: das Projekt mit den meisten TODOs des Ziels
    await waitFor(() => expect(within(dialog).getByLabelText('Project')).toHaveValue('p2'));
    expect(within(dialog).getByLabelText('Time (daily)')).toHaveValue('08:00');
    fireEvent.change(within(dialog).getByLabelText('Time (daily)'), { target: { value: '07:30' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Switch on' }));

    await waitFor(() => expect(state.sent).toEqual([{ enabled: true, projectId: 'p2', cron: '0 30 7 * * *', locale: 'en' }]));
    await waitFor(() => expect(resultTiles()).toEqual(['wf-guard']));
    // wie aus dem Katalog: hinten angehängt, hinter den Standard-Kacheln, und als lokale Änderung vermerkt
    const widgets = selectActiveView(useDashboardLayoutStore.getState())!.widgets;
    expect(widgets.length).toBeGreaterThan(1);
    expect(widgets.at(-1)).toMatchObject({ type: 'workflow-result', config: { workflowId: 'wf-guard' } });
    expect(useDashboardSyncStore.getState().dirty).toBe(true);
    await waitFor(() => expect(toasts()).toContain('Tile added to the dashboard'));

    expect(await screen.findByRole('switch', { name: 'Guard' })).toHaveAttribute('aria-checked', 'true');
    expect(await screen.findByText(/^Next run:/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Workflow' })).toHaveAttribute('href', '/workflows/wf-guard');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('switching off pauses without touching the tile; switching on again asks nothing and adds no second tile', async () => {
    const state = serveGoal(ON);
    useDashboardLayoutStore.getState().setActiveViewWidgets([
      { instanceId: 'workflow-result-1', type: 'workflow-result', config: { workflowId: 'wf-guard' } },
    ]);
    renderWithProviders(<Harness />);

    const toggle = await screen.findByRole('switch', { name: 'Guard' });
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'true'));
    fireEvent.click(toggle);
    await waitFor(() => expect(state.sent).toEqual([{ enabled: false }]));
    expect(await screen.findByText('paused')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Workflow' })).toHaveAttribute('href', '/workflows/wf-guard');
    expect(resultTiles()).toEqual(['wf-guard']);
    expect(toasts()).toContain('Guard paused');

    fireEvent.click(screen.getByRole('switch', { name: 'Guard' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(state.sent).toEqual([{ enabled: false }, { enabled: true }]));
    await waitFor(() => expect(toasts()).toContain('Guard switched on'));
    expect(resultTiles()).toEqual(['wf-guard']);
    expect(toasts()).not.toContain('Tile added to the dashboard');
  });

  it('"Check now" runs the guard workflow', async () => {
    const state = serveGoal(ON);
    renderWithProviders(<Harness />);

    fireEvent.click(await screen.findByRole('button', { name: 'Check now' }));

    await waitFor(() => expect(state.executed).toEqual(['wf-guard']));
  });

  it('cancelling the dialog switches nothing on', async () => {
    const state = serveGoal(OFF);
    renderWithProviders(<Harness />);

    fireEvent.click(await screen.findByRole('switch', { name: 'Guard' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Guard' })).toHaveAttribute('aria-checked', 'false');
    expect(state.sent).toEqual([]);
  });

  // ff6f08fd: the time of the guard changes at the goal page, through the schedule API
  describe('changing the time', () => {
    const loaded = { schedule: 0 };
    function serveSchedule(cronExpression: string) {
      const puts: unknown[] = [];
      loaded.schedule = 0;
      server.use(
        http.get('/api/v1/schedules/s1', () => {
          loaded.schedule += 1;
          return HttpResponse.json({
            id: 's1', workflowId: 'wf-guard', targetType: 'WORKFLOW', scheduleType: 'CRON', cronExpression, enabled: true,
          });
        }),
        http.put('/api/v1/schedules/s1', async ({ request }) => {
          puts.push(await request.json());
          return HttpResponse.json({ id: 's1', cronExpression: (puts.at(-1) as { cronExpression: string }).cronExpression });
        }),
      );
      return puts;
    }

    it('sets the new time of day on the schedule and keeps the guard as it is', async () => {
      const state = serveGoal(ON);
      const puts = serveSchedule('0 0 8 * * *');
      renderWithProviders(<Harness />);

      fireEvent.click(await screen.findByRole('button', { name: 'Change time' }));
      const dialog = screen.getByRole('dialog', { name: 'Time of the guard' });
      const input = within(dialog).getByLabelText('Time (daily)');
      expect(input).toHaveValue('08:00');
      expect(within(dialog).getByRole('button', { name: 'Save' })).toBeDisabled();
      fireEvent.change(input, { target: { value: '06:45' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));

      await waitFor(() => expect(puts).toEqual([{ cronExpression: '0 45 6 * * *' }]));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(toasts()).toContain('Guard time saved');
      expect(state.sent).toEqual([]);
    });

    it('keeps the days of a schedule set to weekdays under "Schedules"', async () => {
      serveGoal(ON);
      const puts = serveSchedule('0 30 7 * * MON-FRI');
      useI18nStore.setState({ locale: 'de' });
      renderWithProviders(<Harness />);

      fireEvent.click(await screen.findByRole('button', { name: 'Uhrzeit ändern' }));
      const dialog = screen.getByRole('dialog', { name: 'Uhrzeit des Wächters' });
      expect(within(dialog).getByText(/die Tage bleiben, wie sie unter „Zeitpläne“ eingestellt sind/)).toBeInTheDocument();
      fireEvent.change(within(dialog).getByLabelText('Uhrzeit'), { target: { value: '09:05' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'Speichern' }));

      await waitFor(() => expect(puts).toEqual([{ cronExpression: '0 5 9 * * MON-FRI' }]));
    });

    it('offers no time for a schedule without a fixed time of day', async () => {
      serveGoal(ON);
      serveSchedule('0 */15 * * * *');
      renderWithProviders(<Harness />);

      expect(await screen.findByRole('button', { name: 'Check now' })).toBeInTheDocument();
      await waitFor(() => expect(loaded.schedule).toBe(1));
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
      expect(screen.queryByRole('button', { name: 'Change time' })).not.toBeInTheDocument();
    });
  });

  it('is not offered while the dashboard-report module is off and no guard exists', async () => {
    serveGoal(OFF);
    server.use(http.get('/api/v1/system/capabilities', () =>
      HttpResponse.json({ blockTypes: [], ...modulesFixture('full', ['dashboard-report']) })));
    renderWithProviders(<Harness />);

    await screen.findByText('goal loaded');
    await waitFor(() => expect(screen.queryByRole('switch', { name: 'Guard' })).not.toBeInTheDocument());
  });
});
