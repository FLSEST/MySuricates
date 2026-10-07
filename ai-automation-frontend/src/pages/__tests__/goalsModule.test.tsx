import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { modulesFixture } from '../../test/modules';
import { useI18nStore } from '../../i18n';
import { useSettingsStore } from '../../stores/settingsStore';
import { useSidebarStore } from '../../stores/sidebarStore';
import { Todos } from '../Todos';
import { ProjectCockpit } from '../ProjectCockpit';
import { GoalDetail } from '../GoalDetail';
import { ModuleRoute } from '../ModuleRoute';
import { Sidebar } from '../../components/layout/Sidebar';
import type { Todo } from '../../types';

// c60bf914 (Flo): „Wird auch sauber durchgetestet, wie die TODO-Seite aussieht, wenn GOALS deaktiviert ist?“ –
// „Ja, das war damals der Plan, dass es ein richtiges Modul ist.“ Every surface with a goal part is tested with the
// module ON and OFF; when off, there must be no call to /goals/** and no planning anymore.

const BASE = '/api/v1';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ simpleMode: false, modeChoiceMade: false, sidebarItemOrder: [], sidebarHiddenItems: [], sidebarDividerLabels: {} });
  useSidebarStore.setState({ collapsed: false });
});

const todo: Todo = {
  id: 't1', title: 'Licence files', description: null, status: 'OPEN', priority: 'MEDIUM', tags: [], projectId: 'p1',
  projectName: 'AutoPilot', linkedWorkflowId: null, linkedWorkflowName: null, source: 'USER', sourceAgent: null,
  workflowRunId: null, referenceType: null, referenceId: null, fixAttempts: 0, lastFixRunId: null, resolutionState: 'IDLE',
  dueDate: null, createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z',
} as Todo;

/**
 * The module on or off – as the server reports it and as the browser knows it from the last load.
 * Every call to /goals/** and to planning is recorded; if the module is off, the server answers
 * like the real one with 404 MODULE_DISABLED.
 */
function serveModule(on: boolean) {
  const modules = modulesFixture('full', on ? [] : ['goals']);
  localStorage.setItem('mysuricates-modules', JSON.stringify(modules));
  const goalCalls: string[] = [];
  const disabled = () => HttpResponse.json({ error: 'Module goals is disabled', code: 'MODULE_DISABLED' }, { status: 404 });
  const record = ({ request }: { request: Request }) => {
    goalCalls.push(`${request.method} ${new URL(request.url).pathname}`);
    return on ? HttpResponse.json([]) : disabled();
  };
  server.use(
    http.get(`${BASE}/system/capabilities`, () => HttpResponse.json({ blockTypes: [], ...modules })),
    http.all(`${BASE}/goals`, record),
    http.all(`${BASE}/goals/*`, record),
    http.patch(`${BASE}/todos/:id/planning`, (info) => record(info)),
    http.get(`${BASE}/todos/overview`, () => HttpResponse.json({
      projects: [], generatedAt: '2026-09-24T08:00:00Z', staleThresholdDays: 30,
      needsYou: [{ id: 't1', title: 'Licence files', projectId: 'p1', projectName: 'AutoPilot', priority: 'HIGH', dueDate: null,
        source: 'USER', createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z', ageDays: 30, overdue: false }],
      // a server with the module off delivers none; a stale intermediate state must still show nothing
      milestonesDue: [{ id: 'm1', kind: 'MILESTONE', title: 'Code freeze', goalId: 'g1', goalTitle: 'Release',
        dueDate: '2026-09-22T21:59:59Z', state: 'OVERDUE', total: 2, done: 1 }],
    })),
    http.get(`${BASE}/todos/count`, () => HttpResponse.json({ open: 1, inProgress: 0, done: 0, backlog: 0, archived: 0 })),
    // when off, the server no longer delivers planning (planning: null)
    http.get(`${BASE}/todos/:id`, () => HttpResponse.json({
      ...todo,
      planning: on ? {
        goalId: 'g1', goalTitle: 'Release', goalRank: 1, complexity: 'M', suggestedEffort: 'high', ratingNote: null,
        ratedBy: 'AI', ratedAt: null, openQuestion: null, workPackageId: null, decision: null, decidedAt: null, decidedBy: null,
        decisionState: 'NONE',
      } : null,
    })),
    http.get(`${BASE}/todos`, () => HttpResponse.json({ content: [todo], page: 0, size: 30, totalElements: 1, totalPages: 1 })),
    http.get(`${BASE}/integrations`, () => HttpResponse.json([])),
    http.get(`${BASE}/todo-triggers`, () => HttpResponse.json([])),
  );
  return goalCalls;
}

describe.each([true, false])('module "goals" %s', (on) => {
  const label = on ? 'on' : 'off';

  it(`TODO page and TODO dialog – planning ${on ? 'shown' : 'hidden'}, ${on ? '' : 'no '}goal calls (${label})`, async () => {
    const goalCalls = serveModule(on);
    renderWithProviders(<Todos />, { route: '/todos?todoId=t1&projectId=p1' });

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByDisplayValue('Licence files')).toBeInTheDocument();
    if (on) {
      expect(await within(dialog).findByTestId('todo-planning')).toBeInTheDocument();
      await waitFor(() => expect(goalCalls).toContain('GET /api/v1/goals'));
    } else {
      // give a gated query the chance to go out anyway
      await new Promise((resolve) => setTimeout(resolve, 100));
      expect(within(dialog).queryByTestId('todo-planning')).not.toBeInTheDocument();
      expect(within(dialog).queryByText('Planning')).not.toBeInTheDocument();
      expect(goalCalls).toEqual([]);
    }
    // the list itself shows the TODO either way – nothing left over, no error card
    expect(screen.getAllByText('Licence files').length).toBeGreaterThan(0);
    expect(screen.queryByText(/disabled/i)).not.toBeInTheDocument();
  });

  it(`cockpit – goal cards and due milestones ${on ? 'shown' : 'gone'} (${label})`, async () => {
    const goalCalls = serveModule(on);
    renderWithProviders(<ProjectCockpit />);

    expect(await screen.findByText('Licence files')).toBeInTheDocument();
    if (on) {
      expect(screen.getByRole('heading', { name: 'Goals' })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /Code freeze/ })).toHaveAttribute('href', '/goals/g1');
      await waitFor(() => expect(goalCalls.length).toBeGreaterThan(0));
    } else {
      await new Promise((resolve) => setTimeout(resolve, 100));
      expect(screen.queryByRole('heading', { name: 'Goals' })).not.toBeInTheDocument();
      expect(screen.queryByRole('link', { name: /Code freeze/ })).not.toBeInTheDocument();
      expect(goalCalls).toEqual([]);
    }
  });

  it(`navigation – the goals entry under the TODOs ${on ? 'shown' : 'hidden'} (${label})`, () => {
    serveModule(on);
    renderWithProviders(<Sidebar />);

    expect(screen.getByRole('link', { name: 'Todos' })).toBeInTheDocument();
    if (on) expect(screen.getByRole('link', { name: 'Goals' })).toHaveAttribute('href', '/goals');
    else expect(screen.queryByRole('link', { name: 'Goals' })).not.toBeInTheDocument();
  });

  it(`direct call of a goal page (${label})`, async () => {
    const goalCalls = serveModule(on);
    renderWithProviders(
      <Routes><Route path="/goals/:goalId" element={<ModuleRoute module="goals"><GoalDetail /></ModuleRoute>} /></Routes>,
      { route: '/goals/x' },
    );

    if (on) {
      await waitFor(() => expect(goalCalls).toContain('GET /api/v1/goals/x'));
    } else {
      // the same hint as every switched-off module, and not a single call on the goals
      expect(await screen.findByRole('heading', { name: 'Module "Goals" is disabled' })).toBeInTheDocument();
      expect(screen.getByText(/goals: true/)).toBeInTheDocument();
      await new Promise((resolve) => setTimeout(resolve, 100));
      expect(goalCalls).toEqual([]);
    }
  });
});
