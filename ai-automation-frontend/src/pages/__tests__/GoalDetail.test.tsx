import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { useToastStore } from '../../stores/toastStore';
import { ToastContainer } from '../../components/ui/ToastContainer';
import { GoalDetail } from '../GoalDetail';
import { applyDecisionToDetail } from '../../components/goal/decision';
import type { GoalDetail as GoalDetailData, GoalRelations, GoalTodoItem, Todo } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useToastStore.setState({ toasts: [] });
});

function item(overrides: Partial<GoalTodoItem>): GoalTodoItem {
  return {
    id: 'x', title: 'X', status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
    goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null,
    openQuestion: null, updatedAt: '2026-09-23T00:00:00Z', dueDate: null, workPackageId: null, ...overrides,
  };
}

/** Ende des lokalen Tages – so speichert der TODO-Dialog eine Fälligkeit. */
const endOf = (iso: string) => new Date(`${iso}T23:59:59`).toISOString();

const detail: GoalDetailData = {
  goal: {
    id: 'g1', title: 'Open-Source-Launch', description: 'Repo public, README with video', status: 'ACTIVE',
    targetDate: '2026-10-15T00:00:00Z', createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
    progress: { total: 4, open: 2, inProgress: 1, backlog: 0, done: 1, openQuestions: 1, percentDone: 25 },
    guard: { enabled: false, workflowId: null, scheduleId: null, nextRunAt: null },
  },
  todos: [
    item({
      id: 'a', title: 'Privacy findings', goalRank: 1, complexity: 'M', suggestedEffort: 'medium', ratedBy: 'AI',
      dueDate: endOf('2030-10-03'), workPackageId: 'w1',
    }),
    item({ id: 'b', title: 'Style probe', goalRank: 2, status: 'IN_PROGRESS', openQuestion: 'Which look?' }),
    item({ id: 'c', title: 'Examples page', projectId: 'p2', projectName: 'Site' }),
    item({ id: 'd', title: 'Banner', status: 'DONE' }),
  ],
  byProject: [
    { projectId: 'p1', projectName: 'AutoPilot', total: 3, done: 1 },
    { projectId: 'p2', projectName: 'Site', total: 1, done: 0 },
  ],
  projects: [
    { projectId: 'p1', name: 'AutoPilot', archived: false, position: 0, total: 3, done: 1, openQuestions: 1 },
    { projectId: 'p2', name: 'Site', archived: false, position: 1, total: 1, done: 0, openQuestions: 0 },
  ],
  workPackages: [{
    id: 'w1', goalId: 'g1', title: 'Beta', startDate: new Date('2030-09-28T00:00:00').toISOString(), endDate: endOf('2030-10-05'),
    projectId: 'p1', position: 0, openQuestions: 0, state: 'OPEN', total: 1, done: 0, completedAt: null, reopened: false,
    createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
  }],
  milestones: [{
    id: 'ms1', goalId: 'g1', title: 'Launch', dueDate: endOf('2030-10-10'), state: 'OPEN', workPackageIds: ['w1'],
    packagesTotal: 1, packagesDone: 0, reachedAt: null, reopened: false, createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
  }],
};

/**
 * Die Antwort eines Backends vor c60bf914: ohne `projects` und `workPackages`; seine „milestones“ sind
 * Arbeitspakete mit nur einer Fälligkeit, die TODOs zeigen per `milestoneId` darauf.
 */
function olderDetail(): Record<string, unknown> {
  const { projects: _projects, workPackages: _packages, milestones: _milestones, ...rest } = detail;
  return {
    ...rest,
    todos: detail.todos.map(({ workPackageId, ...todo }) => ({ ...todo, milestoneId: workPackageId })),
    milestones: detail.workPackages.map(({ id, goalId, title, endDate, state, total, done, createdAt, updatedAt }) => ({
      id, goalId, title, dueDate: endDate, state, total, done, reachedAt: null, reopened: false, createdAt, updatedAt,
    })),
  };
}

/** Die Liste als gewählte Ansicht – die Standardansicht ist seit c60bf914 die Gliederung. */
const chooseList = () => localStorage.setItem('mysuricates-goal-view-v2', 'list');

function candidate(id: string, title: string, tags: string[] = []): Todo {
  return {
    id, title, description: null, status: 'OPEN', priority: 'MEDIUM', tags, projectId: 'p1', projectName: 'AutoPilot',
    linkedWorkflowId: null, source: 'USER', createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
  } as unknown as Todo;
}

const relations: GoalRelations = {
  goalId: 'g1',
  activeTriggers: 0,
  todos: detail.todos.map((todo) => ({
    id: todo.id, title: todo.title, status: todo.status, priority: todo.priority, projectId: todo.projectId,
    projectName: todo.projectName, tags: todo.tags, goalRank: todo.goalRank, candidate: false,
    origin: { kind: 'USER', workflowId: null, workflowName: null, runId: null, runStatus: null, findingIds: [], agent: null },
    workers: [], result: null,
  })),
};

/** Candidates (`goalId=none`) and the relation map; records the asked candidate filters and includes. */
function serveMapAndCandidates(candidates: Todo[], asked: { todos: URLSearchParams[]; include: (string | null)[] }) {
  server.use(
    http.get('/api/v1/todos', ({ request }) => {
      const params = new URL(request.url).searchParams;
      asked.todos.push(params);
      const tag = params.get('tag');
      const content = candidates.filter((c) => !tag || c.tags.includes(tag));
      return HttpResponse.json({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });
    }),
    http.get('/api/v1/goals/g1/relations', ({ request }) => {
      const include = new URL(request.url).searchParams.get('include');
      asked.include.push(include);
      const extra = (include ?? '').split(',').filter(Boolean).map((id) => ({
        ...relations.todos[0]!, id, title: candidates.find((c) => c.id === id)?.title ?? id, candidate: true,
      }));
      return HttpResponse.json({ ...relations, todos: [...relations.todos, ...extra] });
    }),
  );
}

function renderGoal() {
  return renderWithProviders(
    <Routes><Route path="/goals/:goalId" element={<GoalDetail />} /></Routes>,
    { route: '/goals/g1' },
  );
}

describe('GoalDetail', () => {
  it('lists what comes next in work order, the open questions and the progress', async () => {
    chooseList();
    server.use(http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)));
    renderGoal();

    await screen.findByRole('heading', { name: 'Open-Source-Launch' });
    const next = screen.getAllByTestId('goal-next-item').map((li) => within(li).getByRole('button', { name: /^Open TODO: / }).textContent);
    expect(next).toEqual(['Privacy findings', 'Style probe', 'Examples page']);
    expect(screen.getByTestId('goal-question')).toHaveTextContent('Which look?');
    expect(screen.getByTestId('goal-progress')).toHaveTextContent('1 of 4 done');
    const projects = screen.getByRole('group', { name: 'Projects of the goal' });
    expect(within(projects).getByText('Site')).toBeInTheDocument();
    expect(within(projects).getByText('0 of 1 done')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'assessed by AI' })).toBeInTheDocument();
  });

  it('shows the due date and the work package of a TODO as chips in the list', async () => {
    chooseList();
    server.use(http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)));
    renderGoal();

    await screen.findByRole('heading', { name: 'Open-Source-Launch' });
    const [first, second] = screen.getAllByTestId('goal-next-item');
    expect(within(first!).getByTitle('Due 10/3/2030')).toHaveTextContent('Oct 3');
    expect(within(first!).getByTitle('Work package: Beta')).toHaveTextContent('Beta');
    expect(within(second!).queryByTitle(/^Due/)).not.toBeInTheDocument();
    expect(within(second!).queryByTitle(/^Work package/)).not.toBeInTheDocument();
  });

  it('copes with an older backend without milestones, guard and due dates', async () => {
    chooseList();
    const { guard: _guard, ...goalWithoutGuard } = detail.goal;
    const { milestones: _milestones, projects: _projects, workPackages: _packages, ...older } = detail;
    server.use(http.get('/api/v1/goals/g1', () => HttpResponse.json({
      ...older,
      goal: goalWithoutGuard,
      todos: detail.todos.map(({ dueDate: _due, workPackageId: _wp, ...rest }) => rest),
    })));
    renderGoal();

    await screen.findByRole('heading', { name: 'Open-Source-Launch' });
    expect(screen.getAllByTestId('goal-next-item')).toHaveLength(3);
    fireEvent.click(screen.getByRole('tab', { name: 'Timeline' }));
    expect(await screen.findByTestId('goal-timeline')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: 'Outline' }));
    expect(await screen.findByTestId('outline-project-p1')).toBeInTheDocument();
  });

  // c60bf914: the outline is the new default – a view chosen under the old key does not hide it
  it('opens on the outline by default, even with a view stored under the old key', async () => {
    localStorage.setItem('mysuricates-goal-view', 'list');
    server.use(http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)));
    renderGoal();

    const outline = await screen.findByTestId('goal-outline');
    expect(screen.getByRole('tab', { name: 'Outline' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.queryByTestId('goal-next-item')).not.toBeInTheDocument();
    const autopilot = within(outline).getByTestId('outline-project-p1');
    expect(within(autopilot).getByRole('button', { name: 'AutoPilot', expanded: true })).toBeInTheDocument();
    // the header counts the whole project: done/total and the open questions, read out in full
    const [done] = within(autopilot).getAllByTestId('outline-done');
    const [questions] = within(autopilot).getAllByTestId('outline-questions');
    expect(done).toHaveTextContent('1/31 of 3 done');
    expect(questions).toHaveTextContent('1Open questions: 1');
    const beta = within(autopilot).getByTestId('outline-package-w1');
    expect(within(beta).getByRole('button', { name: /^Open TODO: Privacy findings/ })).toBeInTheDocument();
    const loose = within(autopilot).getByTestId('outline-loose-p1');
    expect(within(loose).getAllByTestId('outline-todo').map((row) => within(row).getAllByRole('button')[0]!.textContent))
      .toEqual(['Style probe', 'Banner']);
    // the open question sits on its TODO in the human colour, readable as text
    expect(within(loose).getByRole('img', { name: 'Open question: Which look?' })).toHaveClass('text-human');
    const site = within(outline).getByTestId('outline-project-p2');
    expect(within(site).getByRole('button', { name: /^Open TODO: Examples page/ })).toBeInTheDocument();
  });

  it('builds the outline from an older backend: its milestones as packages in their TODOs\' project, milestones later', async () => {
    server.use(http.get('/api/v1/goals/g1', () => HttpResponse.json(olderDetail())));
    renderGoal();

    const autopilot = await screen.findByTestId('outline-project-p1');
    const beta = within(autopilot).getByTestId('outline-package-w1');
    expect(beta).toHaveTextContent('Beta');
    expect(within(beta).getByRole('button', { name: /^Open TODO: Privacy findings/ })).toBeInTheDocument();
    expect(screen.getByTestId('outline-project-p2')).toBeInTheDocument();
    expect(screen.queryByTestId('outline-project-none')).not.toBeInTheDocument();
    expect(screen.getByTestId('outline-milestones-legacy')).toHaveTextContent('Milestones become available after the backend restart.');
  });

  it('shows the milestones above the projects in the outline', async () => {
    server.use(http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)));
    renderGoal();

    const block = await screen.findByTestId('outline-milestones');
    expect(within(block).getByTestId('outline-milestone-ms1')).toHaveTextContent('Launch');
    expect(within(block).getByTestId('outline-milestone-ms1')).toHaveTextContent('0/1 packages');
  });

  it('removes a project from the goal after asking, and offers the cascade when the server refuses with 409', async () => {
    const deleted: string[] = [];
    server.use(
      http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)),
      http.delete('/api/v1/goals/g1/projects/:pid', ({ request, params }) => {
        const cascade = new URL(request.url).searchParams.get('cascade');
        deleted.push(`${params.pid}?cascade=${cascade}`);
        return cascade === 'true'
          ? new HttpResponse(null, { status: 204 })
          : HttpResponse.json({ error: '1 work package and 3 TODOs are still attached', timestamp: '' }, { status: 409 });
      }),
    );
    renderGoal();

    fireEvent.click(await screen.findByRole('button', { name: 'Remove project AutoPilot from the goal' }));
    const dialog = screen.getByRole('dialog', { name: 'Remove project from the goal' });
    expect(within(dialog).getByText('1 work packages and 3 TODOs are attached to it.')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));

    expect(await within(dialog).findByText('1 work package and 3 TODOs are still attached')).toBeInTheDocument();
    expect(deleted).toEqual(['p1?cascade=false']);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete work packages, release TODOs' }));
    await waitFor(() => expect(deleted).toEqual(['p1?cascade=false', 'p1?cascade=true']));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Remove project from the goal' })).not.toBeInTheDocument());
  });

  it('adds a project from the picker and offers only projects the goal does not have yet', async () => {
    const added: unknown[] = [];
    server.use(
      http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)),
      http.get('/api/v1/projects', () => HttpResponse.json([
        { id: 'p1', name: 'AutoPilot' }, { id: 'p3', name: 'Docs' }, { id: 'p4', name: 'Old', archivedAt: '2026-01-01T00:00:00Z' },
      ])),
      http.post('/api/v1/goals/g1/projects', async ({ request }) => {
        added.push(await request.json());
        return HttpResponse.json(detail);
      }),
    );
    renderGoal();

    const projects = await screen.findByRole('group', { name: 'Projects of the goal' });
    const picker = await within(projects).findByRole('combobox', { name: 'Add project' });
    expect(within(picker).getAllByRole('option').map((option) => option.textContent)).toEqual(['+ Add project', 'Docs']);
    fireEvent.change(picker, { target: { value: 'p3' } });
    await waitFor(() => expect(added).toEqual([{ projectId: 'p3' }]));
  });

  it('switches to the timeline and remembers the choice', async () => {
    server.use(http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)));
    renderGoal();

    fireEvent.click(await screen.findByRole('tab', { name: 'Timeline' }));

    const timeline = await screen.findByTestId('goal-timeline');
    expect(within(timeline).getByTestId('timeline-row-p1-w1')).toBeInTheDocument();
    expect(within(timeline).getByRole('button', { name: /^Milestone Launch/ })).toBeInTheDocument();
    expect(within(timeline).getByRole('button', { name: /^Privacy findings/ })).toBeInTheDocument();
    expect(localStorage.getItem('mysuricates-goal-view-v2')).toBe('timeline');
    expect(screen.getByRole('tab', { name: 'Timeline' })).toHaveAttribute('aria-selected', 'true');
  });

  it('moving an item down sends the whole active order to the server', async () => {
    chooseList();
    let sent: unknown = null;
    server.use(
      http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)),
      http.put('/api/v1/goals/g1/order', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(detail);
      }),
    );
    renderGoal();

    fireEvent.click(await screen.findByRole('button', { name: 'Move down: Privacy findings' }));

    await waitFor(() => expect(sent).toEqual({ todoIds: ['b', 'a', 'c'] }));
    expect(screen.getByRole('button', { name: 'Move up: Privacy findings' })).toBeDisabled();
  });

  it('switches to the relation map, remembers the choice and shows the dock-on hint', async () => {
    const asked = { todos: [] as URLSearchParams[], include: [] as (string | null)[] };
    server.use(http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)));
    serveMapAndCandidates([candidate('c1', 'Release notes', ['release'])], asked);
    renderGoal();

    fireEvent.click(await screen.findByRole('tab', { name: 'Workflow map' }));

    expect(await screen.findByTestId('goal-relation-map')).toBeInTheDocument();
    expect(screen.getByTestId('goal-map-no-workers')).toBeInTheDocument();
    expect(localStorage.getItem('mysuricates-goal-view-v2')).toBe('map');
    // The shown candidates go into the map as `include` and appear there dashed
    await waitFor(() => expect(asked.include).toContain('c1'));
    // once in the map (dashed), once in the list below
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Add: Release notes' })).toHaveLength(2));
    // due date and work package of a TODO also sit on its node
    const node = document.querySelector('[data-node="todo:a"]')!;
    expect(node).toHaveTextContent('Oct 3 · Beta');
    const todoQuery = asked.todos[0]!;
    expect(todoQuery.get('goalId')).toBe('none');
    expect(todoQuery.getAll('status')).toEqual(['OPEN', 'IN_PROGRESS', 'BACKLOG']);
    expect(todoQuery.get('size')).toBe('20');
  });

  it('assigns a candidate with one click and filters candidates by a suggested tag', async () => {
    let assigned: unknown = null;
    const asked = { todos: [] as URLSearchParams[], include: [] as (string | null)[] };
    server.use(
      http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)),
      http.post('/api/v1/goals/g1/todos', async ({ request }) => {
        assigned = await request.json();
        return HttpResponse.json(detail);
      }),
    );
    serveMapAndCandidates([candidate('c1', 'Release notes', ['release']), candidate('c2', 'Unrelated chore')], asked);
    renderGoal();

    expect(await screen.findByText('Unrelated chore')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'release' }));
    await waitFor(() => expect(screen.queryByText('Unrelated chore')).not.toBeInTheDocument());
    expect(asked.todos.at(-1)!.get('tag')).toBe('release');

    fireEvent.click(screen.getByRole('button', { name: 'Add: Release notes' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'No work package' }));
    await waitFor(() => expect(assigned).toEqual({ todoIds: ['c1'] }));
  });

  // c9fcccc3: the candidate leaves the list at once – the toast offers to take it back
  it('undoes an assignment from the toast by releasing the todo from the goal', async () => {
    const assigned: unknown[] = [];
    const planned: { id: string; body: unknown }[] = [];
    const asked = { todos: [] as URLSearchParams[], include: [] as (string | null)[] };
    server.use(
      http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)),
      http.post('/api/v1/goals/g1/todos', async ({ request }) => {
        assigned.push(await request.json());
        return HttpResponse.json(detail);
      }),
      http.patch('/api/v1/todos/:id/planning', async ({ params, request }) => {
        planned.push({ id: String(params.id), body: await request.json() });
        return HttpResponse.json(candidate(String(params.id), 'Release notes'));
      }),
    );
    serveMapAndCandidates([candidate('c1', 'Release notes', ['release'])], asked);
    renderWithProviders(
      <>
        <Routes><Route path="/goals/:goalId" element={<GoalDetail />} /></Routes>
        <ToastContainer />
      </>,
      { route: '/goals/g1' },
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Add: Release notes' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'No work package' }));
    await waitFor(() => expect(assigned).toEqual([{ todoIds: ['c1'] }]));
    const toast = await screen.findByText('Added: AutoPilot · No work package');
    fireEvent.click(within(toast.closest('[data-testid="toast"]') as HTMLElement).getByRole('button', { name: 'Undo' }));

    // goalId '' releases the todo; null would mean "unchanged" to the planning endpoint
    await waitFor(() => expect(planned).toEqual([{ id: 'c1', body: { goalId: '' } }]));
    expect(await screen.findByText('Removed from the goal again')).toBeInTheDocument();
  });

  // c60bf914 (Flo): "Might belong here also shows TODOs from other projects that are not assigned"
  it('takes candidates from the goal\'s projects only, and from all projects with the switch', async () => {
    const asked: URLSearchParams[] = [];
    const foreign = { ...candidate('c2', 'Other project chore'), projectId: 'p9', projectName: 'Elsewhere' } as Todo;
    server.use(
      http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)),
      http.get('/api/v1/goals/g1/candidates', ({ request }) => {
        const params = new URL(request.url).searchParams;
        asked.push(params);
        const content = params.get('allProjects') === 'true' ? [candidate('c1', 'Release notes'), foreign] : [candidate('c1', 'Release notes')];
        return HttpResponse.json({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });
      }),
    );
    renderGoal();

    expect(await screen.findByText('Release notes')).toBeInTheDocument();
    expect(screen.queryByText('Other project chore')).not.toBeInTheDocument();
    expect(screen.getByText("Open TODOs without a goal from this goal's projects; the ones shown appear dashed in the map.")).toBeInTheDocument();
    expect(asked[0]!.get('allProjects')).toBe('false');
    expect(asked[0]!.get('size')).toBe('20');

    fireEvent.click(screen.getByRole('switch', { name: 'Show all projects' }));
    const row = (await screen.findByText('Other project chore')).closest('li')!;
    expect(within(row).getByText('other project')).toHaveAttribute('title', 'The project does not belong to the goal yet – adding the TODO adds it.');
    expect(asked.at(-1)!.get('allProjects')).toBe('true');
    // TODOs of the goal's own projects carry no mark
    expect(within(screen.getByText('Release notes').closest('li')!).queryByText('other project')).not.toBeInTheDocument();
  });

  it('keeps the old way over /todos for a backend without the candidates endpoint, without the switch', async () => {
    const asked = { todos: [] as URLSearchParams[], include: [] as (string | null)[] };
    server.use(http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)));
    serveMapAndCandidates([candidate('c1', 'Release notes')], asked);
    renderGoal();

    expect(await screen.findByText('Release notes')).toBeInTheDocument();
    expect(asked.todos[0]!.get('goalId')).toBe('none');
    expect(screen.queryByRole('switch', { name: 'Show all projects' })).not.toBeInTheDocument();
    expect(screen.getByText('Open TODOs without a goal from all projects; the ones shown appear dashed in the map.')).toBeInTheDocument();
  });

  it('says why the list is empty when the goal has no project yet', async () => {
    server.use(
      http.get('/api/v1/goals/g1', () => HttpResponse.json({ ...detail, projects: [] })),
      http.get('/api/v1/goals/g1/candidates', () => HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })),
    );
    renderGoal();

    expect(await screen.findByText('No project is assigned to this goal yet. “Show all projects” searches everywhere.')).toBeInTheDocument();
  });

  // 68a5e24e: „Entschieden“ als sichtbarer Zustand – entscheiden aus der Liste, ändern und entfernen
  describe('decisions', () => {
    const decidedAt = '2026-09-27T10:00:00Z';
    const deciding: GoalDetailData = {
      ...detail,
      goal: { ...detail.goal, progress: { ...detail.goal.progress, decided: 1 } },
      todos: detail.todos.map((todo) => ({
        ...todo, decision: null, decidedAt: null, decidedBy: null,
        decisionState: todo.openQuestion ? 'QUESTION' as const : 'NONE' as const,
        ...(todo.id === 'c' ? { decision: 'Plain HTML', decidedAt, decidedBy: 'USER' as const, decisionState: 'DECIDED' as const } : {}),
      })),
    };
    /** Das Ziel antwortet mit dem jeweils aktuellen Stand – wie der Server nach dem Entscheiden. */
    function serveDecisions() {
      const sent: { id: string; body: unknown }[] = [];
      let state = deciding;
      server.use(
        http.get('/api/v1/goals/g1', () => HttpResponse.json(state)),
        http.patch('/api/v1/todos/:id/planning', async ({ params, request }) => {
          const body = (await request.json()) as { decision: string };
          sent.push({ id: String(params.id), body });
          state = applyDecisionToDetail(state, String(params.id), body.decision, decidedAt);
          return HttpResponse.json(candidate(String(params.id), 'x'));
        }),
      );
      return sent;
    }

    it('decides an open question from the list; it moves to "Decided" and the progress counts it', async () => {
      chooseList();
      const sent = serveDecisions();
      renderGoal();

      const progress = within((await screen.findByTestId('goal-progress')).parentElement!).getByTestId('decision-counts');
      expect(progress).toHaveTextContent('1 open·1 decided');
      fireEvent.click(screen.getByRole('button', { name: 'Decide: Style probe' }));
      const card = screen.getByRole('dialog', { name: 'Decision on Style probe' });
      expect(within(card).getByText('Which look?')).toBeInTheDocument();
      expect(within(card).getByText('Saving closes the open question.')).toBeInTheDocument();
      fireEvent.change(within(card).getByLabelText('Decision'), { target: { value: ' Dark theme ' } });
      fireEvent.click(within(card).getByRole('button', { name: 'Decide' }));

      await waitFor(() => expect(sent).toEqual([{ id: 'b', body: { decision: 'Dark theme' } }]));
      // shown at once: no open question left, two decided
      await waitFor(() => expect(screen.queryAllByTestId('goal-question')).toHaveLength(0));
      expect(screen.getByText('No open questions.', { exact: false })).toBeInTheDocument();
      expect(screen.getByText('Decided (2)')).toBeInTheDocument();
    });

    it('shows a decision with its wording, date and who, and changes or removes it', async () => {
      chooseList();
      const sent = serveDecisions();
      renderGoal();

      fireEvent.click(await screen.findByText('Decided (1)'));
      const row = screen.getByTestId('goal-decided-item');
      expect(row).toHaveTextContent('Examples page');
      expect(row).toHaveTextContent('Plain HTML');
      expect(row).toHaveTextContent('9/27/2026, by a person');
      const mark = within(row).getByRole('button', { name: /^Decided: Plain HTML \(9\/27\/2026, by a person\) – Change decision: Examples page$/ });
      fireEvent.click(mark);
      let card = screen.getByRole('dialog', { name: 'Decision on Examples page' });
      fireEvent.click(within(card).getByRole('button', { name: 'Change' }));
      fireEvent.change(within(card).getByLabelText('Decision'), { target: { value: 'Astro' } });
      fireEvent.click(within(card).getByRole('button', { name: 'Save' }));
      await waitFor(() => expect(sent).toEqual([{ id: 'c', body: { decision: 'Astro' } }]));

      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Decision on Examples page' })).not.toBeInTheDocument());
      fireEvent.click(within(screen.getByTestId('goal-decided-item')).getByRole('button', { name: /Change decision: Examples page$/ }));
      card = screen.getByRole('dialog', { name: 'Decision on Examples page' });
      fireEvent.click(within(card).getByRole('button', { name: 'Remove' }));
      await waitFor(() => expect(sent).toContainEqual({ id: 'c', body: { decision: '' } }));
    });

    // Review 68a5e24e: shown at once, taken back when the server refuses – with the server's message
    it('takes an optimistic decision back when the server refuses it, and says why', async () => {
      chooseList();
      let refuse = () => {};
      server.use(
        http.get('/api/v1/goals/g1', () => HttpResponse.json(deciding)),
        http.patch('/api/v1/todos/:id/planning', async () => {
          await new Promise<void>((resolve) => { refuse = resolve; });
          return HttpResponse.json({ error: 'openQuestion and decision at once' }, { status: 400 });
        }),
      );
      renderWithProviders(
        <>
          <Routes><Route path="/goals/:goalId" element={<GoalDetail />} /></Routes>
          <ToastContainer />
        </>,
        { route: '/goals/g1' },
      );

      fireEvent.click(await screen.findByRole('button', { name: 'Decide: Style probe' }));
      const card = screen.getByRole('dialog', { name: 'Decision on Style probe' });
      fireEvent.change(within(card).getByLabelText('Decision'), { target: { value: 'Dark theme' } });
      fireEvent.click(within(card).getByRole('button', { name: 'Decide' }));

      // at once: the question has left, two are decided
      await waitFor(() => expect(screen.queryAllByTestId('goal-question')).toHaveLength(0));
      expect(screen.getByText('Decided (2)')).toBeInTheDocument();

      refuse();
      expect(await screen.findByText('Could not save the decision: openQuestion and decision at once')).toBeInTheDocument();
      await waitFor(() => expect(screen.getAllByTestId('goal-question')).toHaveLength(1));
      expect(screen.getByTestId('goal-question')).toHaveTextContent('Which look?');
      expect(screen.getByText('Decided (1)')).toBeInTheDocument();
      expect(screen.getByTestId('goal-progress').parentElement).toHaveTextContent('1 open·1 decided');
      // c60bf914: the card hangs on the list, not on the row – the typed answer survives the refusal
      expect(within(screen.getByRole('dialog', { name: 'Decision on Style probe' })).getByLabelText('Decision')).toHaveValue('Dark theme');
    });

    it('offers no deciding against an older backend – the marks stay a plain display', async () => {
      chooseList();
      server.use(http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)));
      renderGoal();

      expect(await screen.findByTestId('goal-question')).toHaveTextContent('Which look?');
      expect(screen.queryByRole('button', { name: 'Decide: Style probe' })).not.toBeInTheDocument();
      expect(screen.getAllByTestId('decision-mark').every((mark) => mark.tagName.toLowerCase() === 'svg')).toBe(true);
      expect(screen.queryByTestId('goal-decided')).not.toBeInTheDocument();
    });
  });

  // 52bc1e8c: Workflows am Ziel oder Arbeitspaket anzeigen, starten und als Stand nutzen
  describe('workflows', () => {
    const guardOn = { ...detail, goal: { ...detail.goal, guard: { enabled: true, workflowId: 'wf-guard', scheduleId: 's1', nextRunAt: null } } };
    const attachments = [
      { id: 'guard', goalId: 'g1', workPackageId: null, workflowId: 'wf-guard', workflowName: 'Goal guard: Open-Source-Launch', workflowProjectId: 'p1', role: 'STATUS', guard: true },
      { id: 'a1', goalId: 'g1', workPackageId: null, workflowId: 'wf-check', workflowName: 'Release check', workflowProjectId: 'p1', role: 'STATUS', guard: false },
      { id: 'a2', goalId: 'g1', workPackageId: 'w1', workflowId: 'wf-fix', workflowName: 'Fix bot', workflowProjectId: 'p1', role: 'WORKER', guard: false },
    ];
    const hoursAgo = (n: number) => new Date(Date.now() - n * 3_600_000).toISOString();
    const results = [
      { workflowId: 'wf-check', workflowName: 'Release check', available: true,
        run: { runId: 'run-7', status: 'COMPLETED', startedAt: hoursAgo(2), completedAt: hoursAgo(2), durationMs: 5, gateStatus: 'YELLOW' },
        resultState: 'VALID', result: { title: '2 overdue', status: 'YELLOW', kpis: [{ label: 'Overdue', value: '2' }], items: [] } },
      { workflowId: 'wf-fix', workflowName: 'Fix bot', available: true,
        run: { runId: 'run-8', status: 'FAILED', startedAt: hoursAgo(1), completedAt: hoursAgo(1), durationMs: 5, gateStatus: null },
        resultState: 'MISSING', result: null },
    ];
    function serveWorkflows() {
      const sent: { method: string; path: string; body: unknown }[] = [];
      const record = async ({ request }: { request: Request }) => {
        const body = request.method === 'DELETE' ? null : await request.json();
        sent.push({ method: request.method, path: new URL(request.url).pathname, body });
        if (request.method === 'DELETE') return new HttpResponse(null, { status: 204 });
        if (request.url.includes('/execute')) return HttpResponse.json({ id: 'run-42', status: 'PENDING' });
        return HttpResponse.json({ ...attachments[1], ...(body as object) }, { status: request.method === 'POST' ? 201 : 200 });
      };
      server.use(
        http.get('/api/v1/goals/g1', () => HttpResponse.json(guardOn)),
        http.get('/api/v1/goals/g1/workflows', () => HttpResponse.json(attachments)),
        http.get('/api/v1/dashboard/workflow-results', () => HttpResponse.json(results)),
        http.get('/api/v1/workflows', () => HttpResponse.json([
          { id: 'wf-check', projectId: 'p1', projectName: 'AutoPilot', name: 'Release check', blocks: [], createdAt: '', updatedAt: '' },
          { id: 'wf-docs', projectId: 'p2', projectName: 'Site', name: 'Docs build', blocks: [], createdAt: '', updatedAt: '' },
          { id: 'wf-other', projectId: 'p9', projectName: 'Elsewhere', name: 'Backup', blocks: [], createdAt: '', updatedAt: '' },
        ])),
        http.post('/api/v1/goals/g1/workflows', record),
        http.patch('/api/v1/goals/g1/workflows/:id', record),
        http.delete('/api/v1/goals/g1/workflows/:id', record),
        http.post('/api/v1/workflows/:id/execute', record),
      );
      return sent;
    }

    it('lists the guard and the goal\'s workflows with their state, and starts one with the goal as context', async () => {
      const sent = serveWorkflows();
      renderWithProviders(
        <>
          <Routes><Route path="/goals/:goalId" element={<GoalDetail />} /></Routes>
          <ToastContainer />
        </>,
        { route: '/goals/g1' },
      );

      const panel = await screen.findByTestId('goal-workflows');
      const check = await within(panel).findByRole('button', { name: /^Release check, Reports the state, yellow, 2 hours ago$/ });
      expect(within(panel).getByTestId('goal-workflow-guard')).toHaveTextContent('Guard');
      expect(within(panel).getByTestId('goal-workflow-a1')).toHaveAttribute('data-tone', 'warning');
      // the package workflow sits at its package, not at the goal
      expect(within(panel).queryByTestId('goal-workflow-a2')).not.toBeInTheDocument();
      expect(within(screen.getByTestId('package-workflows-w1')).getByTestId('goal-workflow-a2')).toHaveAttribute('data-tone', 'danger');

      fireEvent.click(within(panel).getByRole('button', { name: 'Start: Release check' }));
      await waitFor(() => expect(sent).toEqual([{
        method: 'POST', path: '/api/v1/workflows/wf-check/execute', body: { context: { goal_id: 'g1', goal_locale: 'en' } },
      }]));
      const toast = await screen.findByText('Release check started');
      expect(within(toast.closest('[data-testid="toast"]') as HTMLElement).getByRole('button', { name: 'Open run' })).toBeInTheDocument();

      fireEvent.click(within(screen.getByTestId('package-workflows-w1')).getByRole('button', { name: 'Start: Fix bot' }));
      await waitFor(() => expect(sent).toContainEqual({
        method: 'POST', path: '/api/v1/workflows/wf-fix/execute', body: { context: { goal_id: 'g1', work_package_id: 'w1', goal_locale: 'en' } },
      }));

      // the card of a status workflow shows its result, lets change the role and remove it
      fireEvent.click(check);
      const card = screen.getByRole('dialog', { name: 'Workflow Release check' });
      expect(within(card).getByTestId('goal-workflow-result')).toHaveTextContent('yellow – 2 overdue');
      expect(within(card).getByText('Overdue')).toBeInTheDocument();
      fireEvent.click(within(card).getByRole('radio', { name: 'Works on it' }));
      await waitFor(() => expect(sent).toContainEqual({ method: 'PATCH', path: '/api/v1/goals/g1/workflows/a1', body: { role: 'WORKER' } }));
      fireEvent.click(within(card).getByRole('button', { name: 'Remove' }));
      await waitFor(() => expect(sent).toContainEqual({ method: 'DELETE', path: '/api/v1/goals/g1/workflows/a1', body: null }));
    });

    it('offers no removing for the guard – it points to its switch', async () => {
      serveWorkflows();
      renderGoal();

      fireEvent.click(await screen.findByRole('button', { name: /^Guard, Reports the state/ }));
      const card = screen.getByRole('dialog', { name: 'Workflow Goal guard: Open-Source-Launch' });
      expect(card).toHaveTextContent('The guard is switched on and off with its switch above.');
      expect(within(card).queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument();
      expect(within(card).queryByRole('radio')).not.toBeInTheDocument();
    });

    it('assigns a workflow of the goal\'s projects to a work package with a role; others only with "show all"', async () => {
      const sent = serveWorkflows();
      renderGoal();

      fireEvent.click(await screen.findByRole('button', { name: 'Assign workflow' }));
      const dialog = screen.getByRole('dialog', { name: 'Assign a workflow' });
      await within(dialog).findByRole('radio', { name: /Docs build/ });
      // on the whole goal Release check hangs already
      expect(within(dialog).getByRole('radio', { name: /Release check/ })).toBeDisabled();
      expect(within(dialog).queryByRole('radio', { name: /Backup/ })).not.toBeInTheDocument();
      fireEvent.click(within(dialog).getByRole('switch', { name: 'Show all workflows' }));
      expect(within(dialog).getByRole('radio', { name: /Backup/ })).toBeInTheDocument();

      fireEvent.change(within(dialog).getByLabelText('For'), { target: { value: 'w1' } });
      // at the package it is free again
      fireEvent.click(within(dialog).getByRole('radio', { name: /Release check/ }));
      fireEvent.click(within(dialog).getByRole('radio', { name: /Reports the state/ }));
      fireEvent.click(within(dialog).getByRole('button', { name: 'Assign' }));
      await waitFor(() => expect(sent).toEqual([{
        method: 'POST', path: '/api/v1/goals/g1/workflows', body: { workflowId: 'wf-check', role: 'STATUS', workPackageId: 'w1' },
      }]));
    });

    it('shows only the switched-on guard for a backend without goal workflows, and says when assigning comes', async () => {
      server.use(http.get('/api/v1/goals/g1', () => HttpResponse.json(guardOn)));
      renderGoal();

      const panel = await screen.findByTestId('goal-workflows');
      expect(within(panel).getByTestId('goal-workflow-guard')).toBeInTheDocument();
      expect(panel).toHaveTextContent('Assigning workflows becomes available after the backend restart.');
      expect(within(panel).queryByRole('button', { name: 'Assign workflow' })).not.toBeInTheDocument();
    });

    it('shows no panel for an older backend while the guard is off', async () => {
      server.use(http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)));
      renderGoal();

      await screen.findByRole('heading', { name: 'Open-Source-Launch' });
      await waitFor(() => expect(screen.queryByTestId('goal-workflows')).not.toBeInTheDocument());
    });
  });

  it('opens the edit dialog of a todo from the map', async () => {
    localStorage.setItem('mysuricates-goal-view-v2', 'map');
    server.use(
      http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)),
      http.get('/api/v1/todos/a', () => HttpResponse.json({ ...candidate('a', 'Privacy findings'), description: 'Full text' })),
    );
    serveMapAndCandidates([], { todos: [], include: [] });
    renderGoal();

    fireEvent.click(await screen.findByRole('button', { name: 'Privacy findings' }));

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(await screen.findByDisplayValue('Full text')).toBeInTheDocument();
  });
  it('explains an older backend (404 on the map) with a hint and a way back to the list, no error card', async () => {
    localStorage.setItem('mysuricates-goal-view-v2', 'map');
    let hits = 0;
    server.use(
      http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)),
      http.get('/api/v1/todos', () => HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 1 })),
      http.get('/api/v1/goals/g1/relations', () => {
        hits += 1;
        return HttpResponse.json({ error: 'No static resource', timestamp: '' }, { status: 404 });
      }),
    );
    renderGoal();

    const hint = await screen.findByTestId('goal-map-outdated-backend');
    expect(hint).toHaveTextContent('The map needs a newer backend – restart the backend; the list keeps working.');
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    expect(hits).toBe(1);

    fireEvent.click(within(hint).getByRole('button', { name: 'To the list' }));
    expect(await screen.findAllByTestId('goal-next-item')).toHaveLength(3);
    expect(screen.getByRole('tab', { name: 'List' })).toHaveAttribute('aria-selected', 'true');
    expect(localStorage.getItem('mysuricates-goal-view-v2')).toBe('list');
  });

  it('shows the empty state in the map for a goal without todos and without candidates', async () => {
    localStorage.setItem('mysuricates-goal-view-v2', 'map');
    const empty: GoalDetailData = {
      ...detail,
      goal: { ...detail.goal, progress: { total: 0, open: 0, inProgress: 0, backlog: 0, done: 0, openQuestions: 0, percentDone: 0 } },
      todos: [],
      byProject: [],
    };
    server.use(
      http.get('/api/v1/goals/g1', () => HttpResponse.json(empty)),
      http.get('/api/v1/todos', () => HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 1 })),
      http.get('/api/v1/goals/g1/relations', () => HttpResponse.json({ goalId: 'g1', activeTriggers: 0, todos: [] })),
    );
    renderGoal();

    expect(await screen.findByRole('button', { name: 'No TODOs yet – add some below under “Might belong here”' })).toBeInTheDocument();
    expect(screen.queryByTestId('goal-relation-map')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  // 23635ef6: the app's own dialog instead of window.confirm
  it('deletes the goal only after confirmation and goes back to the goals', async () => {
    const deleted: string[] = [];
    server.use(
      http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)),
      http.delete('/api/v1/goals/g1', () => {
        deleted.push('g1');
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderWithProviders(
      <Routes>
        <Route path="/goals/:goalId" element={<GoalDetail />} />
        <Route path="/goals" element={<p>goal list</p>} />
      </Routes>,
      { route: '/goals/g1' },
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Delete goal' }));
    const dialog = screen.getByRole('dialog', { name: 'Delete goal' });
    expect(within(dialog).getByText('Delete this goal? Its TODOs stay and only lose the assignment.')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(deleted).toEqual([]);

    fireEvent.click(screen.getByRole('button', { name: 'Delete goal' }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Delete goal' })).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('goal list')).toBeInTheDocument();
    expect(deleted).toEqual(['g1']);
  });

  it('keeps the error card with retry for other errors of the map', async () => {
    localStorage.setItem('mysuricates-goal-view-v2', 'map');
    server.use(
      http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)),
      http.get('/api/v1/todos', () => HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 1 })),
      http.get('/api/v1/goals/g1/relations', () => HttpResponse.json({ error: 'boom', timestamp: '' }, { status: 500 })),
    );
    renderGoal();

    expect(await screen.findByRole('button', { name: 'Retry' }, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByText('boom')).toBeInTheDocument();
    expect(screen.queryByTestId('goal-map-outdated-backend')).not.toBeInTheDocument();
  });
});
