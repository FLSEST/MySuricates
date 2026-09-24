import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
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

describe('GoalGuardControl', () => {
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

  it('is not offered while the dashboard-report module is off and no guard exists', async () => {
    serveGoal(OFF);
    server.use(http.get('/api/v1/system/capabilities', () =>
      HttpResponse.json({ blockTypes: [], ...modulesFixture('full', ['dashboard-report']) })));
    renderWithProviders(<Harness />);

    await screen.findByText('goal loaded');
    await waitFor(() => expect(screen.queryByRole('switch', { name: 'Guard' })).not.toBeInTheDocument());
  });
});
