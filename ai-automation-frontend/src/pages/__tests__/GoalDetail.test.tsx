import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { GoalDetail } from '../GoalDetail';
import type { GoalDetail as GoalDetailData, GoalRelations, GoalTodoItem, Todo } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

function item(overrides: Partial<GoalTodoItem>): GoalTodoItem {
  return {
    id: 'x', title: 'X', status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
    goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null,
    openQuestion: null, updatedAt: '2026-09-23T00:00:00Z', ...overrides,
  };
}

const detail: GoalDetailData = {
  goal: {
    id: 'g1', title: 'Open-Source-Launch', description: 'Repo public, README with video', status: 'ACTIVE',
    targetDate: '2026-10-15T00:00:00Z', createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
    progress: { total: 4, open: 2, inProgress: 1, backlog: 0, done: 1, openQuestions: 1, percentDone: 25 },
  },
  todos: [
    item({ id: 'a', title: 'Privacy findings', goalRank: 1, complexity: 'M', suggestedEffort: 'medium', ratedBy: 'AI' }),
    item({ id: 'b', title: 'Style probe', goalRank: 2, status: 'IN_PROGRESS', openQuestion: 'Which look?' }),
    item({ id: 'c', title: 'Examples page', projectName: 'Site' }),
    item({ id: 'd', title: 'Banner', status: 'DONE' }),
  ],
  byProject: [
    { projectId: 'p1', projectName: 'AutoPilot', total: 3, done: 1 },
    { projectId: 'p2', projectName: 'Site', total: 1, done: 0 },
  ],
};

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
    server.use(http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)));
    renderGoal();

    await screen.findByRole('heading', { name: 'Open-Source-Launch' });
    const next = screen.getAllByTestId('goal-next-item').map((li) => within(li).getByRole('link').textContent);
    expect(next).toEqual(['Privacy findings', 'Style probe', 'Examples page']);
    expect(screen.getByTestId('goal-question')).toHaveTextContent('Which look?');
    expect(screen.getByTestId('goal-progress')).toHaveTextContent('1 of 4 done');
    expect(screen.getByText('Site: 0/1')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'assessed by AI' })).toBeInTheDocument();
  });

  it('moving an item down sends the whole active order to the server', async () => {
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

    fireEvent.click(await screen.findByRole('tab', { name: 'Map' }));

    expect(await screen.findByTestId('goal-relation-map')).toBeInTheDocument();
    expect(screen.getByTestId('goal-map-no-workers')).toBeInTheDocument();
    expect(localStorage.getItem('mysuricates-goal-view')).toBe('map');
    // The shown candidates go into the map as `include` and appear there dashed
    await waitFor(() => expect(asked.include).toContain('c1'));
    // once in the map (dashed), once in the list below
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Add: Release notes' })).toHaveLength(2));
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
    await waitFor(() => expect(assigned).toEqual({ todoIds: ['c1'] }));
  });

  it('opens the edit dialog of a todo from the map', async () => {
    localStorage.setItem('mysuricates-goal-view', 'map');
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
});
