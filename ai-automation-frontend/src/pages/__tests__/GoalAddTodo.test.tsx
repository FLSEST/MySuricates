import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { act, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { useToastStore } from '../../stores/toastStore';
import { ToastContainer } from '../../components/ui/ToastContainer';
import { useGoalReveal } from '../../components/goal/goalReveal';
import { GoalDetail } from '../GoalDetail';
import type { GoalDetail as GoalDetailData, GoalTodoItem, GoalWorkPackage, Todo } from '../../types';

// 85889063 (Flo): „Wie funktioniert das mit dem Add bei TODOs auf der Goals-Seite, sollte man dort ja einem
// Arbeitspaket zuordnen können.“ – „Ich habe + gedrückt und sehe es aber nicht in der Timeline oben“

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
const scrolled = vi.fn();
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useToastStore.setState({ toasts: [] });
  useGoalReveal.setState({ request: null });
  scrolled.mockReset();
  Element.prototype.scrollIntoView = scrolled;
});

function item(overrides: Partial<GoalTodoItem>): GoalTodoItem {
  return {
    id: 'x', title: 'X', status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
    goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null,
    openQuestion: null, updatedAt: '2026-09-23T00:00:00Z', dueDate: null, workPackageId: null, ...overrides,
  };
}

function pkg(id: string, title: string, projectId: string, position: number): GoalWorkPackage {
  return {
    id, goalId: 'g1', title, projectId, position, startDate: null, endDate: null, state: 'OPEN', total: 0, done: 0,
    openQuestions: 0, decided: 0, completedAt: null, reopened: false, createdAt: '', updatedAt: '',
  };
}

function todo(id: string, title: string, projectId: string, projectName: string): Todo {
  return {
    id, title, description: null, status: 'OPEN', priority: 'MEDIUM', tags: [], projectId, projectName,
    linkedWorkflowId: null, source: 'USER', createdAt: '', updatedAt: '',
  } as unknown as Todo;
}

const base: GoalDetailData = {
  goal: {
    id: 'g1', title: 'Launch', description: null, status: 'ACTIVE', targetDate: null, createdAt: '', updatedAt: '',
    progress: { total: 2, open: 2, inProgress: 0, backlog: 0, done: 0, openQuestions: 0, decided: 0, percentDone: 0 },
    guard: { enabled: false, workflowId: null, scheduleId: null, nextRunAt: null },
  },
  todos: [item({ id: 'a', title: 'Licence files', workPackageId: 'w1' }), item({ id: 'b', title: 'Changelog' })],
  byProject: [{ projectId: 'p1', projectName: 'AutoPilot', total: 2, done: 0 }],
  projects: [
    { projectId: 'p1', name: 'AutoPilot', archived: false, position: 0, total: 2, done: 0, openQuestions: 0, decided: 0 },
    { projectId: 'p2', name: 'Site', archived: false, position: 1, total: 0, done: 0, openQuestions: 0, decided: 0 },
  ],
  // absichtlich nicht in Reihenfolge: das Menü ordnet nach `position`
  // w1 mit Zeitraum (eine Zeile im Zeitstrahl), w2 ohne (in der Leiste „Ohne Zeitraum“)
  workPackages: [
    pkg('w2', 'Getting started', 'p1', 1),
    { ...pkg('w1', 'Repo clean', 'p1', 0), startDate: new Date('2030-10-01T00:00:00').toISOString(), endDate: new Date('2030-10-07T23:59:59').toISOString() },
    pkg('w3', 'Site pages', 'p2', 0),
  ],
  milestones: [],
};

const pool = [
  todo('c1', 'Release notes', 'p1', 'AutoPilot'), todo('c2', 'Favicon', 'p2', 'Site'), todo('c3', 'Relaunch site', 'p2', 'Site'),
  // aus einem Projekt ohne Arbeitspakete im Ziel
  todo('c4', 'Backup rotation', 'p9', 'Homelab'),
];

interface Sent { method: string; path: string; body: unknown }

/** Ein kleines Backend mit Gedächtnis: was zugeordnet oder angelegt wird, steht danach im Ziel. */
function serve() {
  const sent: Sent[] = [];
  const todos = [...base.todos];
  const candidates = [...pool];
  const record = async (request: Request) => {
    const body = request.method === 'DELETE' ? null : await request.json();
    sent.push({ method: request.method, path: new URL(request.url).pathname, body });
    return body as Record<string, string>;
  };
  const place = (source: Todo, body: Record<string, string>) => {
    const at = todos.findIndex((entry) => entry.id === source.id);
    if (at >= 0) todos.splice(at, 1);
    if (body.goalId) todos.push(item({ id: source.id, title: source.title, projectId: source.projectId, projectName: source.projectName, workPackageId: body.workPackageId ?? null }));
  };
  server.use(
    http.get('/api/v1/goals/g1', () => HttpResponse.json({ ...base, todos })),
    http.get('/api/v1/goals/g1/candidates', ({ request }) => {
      const q = (new URL(request.url).searchParams.get('q') ?? '').toLowerCase();
      const content = candidates.filter((entry) => !todos.some((own) => own.id === entry.id) && entry.title.toLowerCase().includes(q));
      return HttpResponse.json({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });
    }),
    http.post('/api/v1/goals/g1/todos', async ({ request }) => {
      const body = await record(request) as unknown as { todoIds: string[] };
      body.todoIds.forEach((id) => place(candidates.find((entry) => entry.id === id)!, { goalId: 'g1' }));
      return HttpResponse.json({ ...base, todos });
    }),
    http.patch('/api/v1/todos/:id/planning', async ({ params, request }) => {
      const body = await record(request);
      const source = candidates.find((entry) => entry.id === params.id)!;
      place(source, body);
      return HttpResponse.json(source);
    }),
    http.post('/api/v1/todos', async ({ request }) => {
      const body = await record(request);
      const created = todo('n1', body.title!, body.projectId!, 'AutoPilot');
      candidates.push(created);
      return HttpResponse.json(created, { status: 201 });
    }),
    http.put('/api/v1/todos/:id', async ({ request, params }) => {
      const body = await record(request);
      return HttpResponse.json({ ...candidates.find((entry) => entry.id === params.id), ...body });
    }),
    http.delete('/api/v1/todos/:id', async ({ request }) => {
      await record(request);
      return new HttpResponse(null, { status: 204 });
    }),
  );
  return sent;
}

function renderGoal(route = '/goals/g1') {
  renderWithProviders(
    <>
      <Routes><Route path="/goals/:goalId" element={<GoalDetail />} /></Routes>
      <ToastContainer />
    </>,
    { route },
  );
}

const view = (name: 'outline' | 'list' | 'timeline' | 'map') => localStorage.setItem('mysuricates-goal-view-v2', name);
const toastButton = async (message: string, name: string) => {
  const toast = (await screen.findByText(message)).closest('[data-testid="toast"]') as HTMLElement;
  return within(toast).getByRole('button', { name });
};
const revealedRow = () => document.querySelector<HTMLElement>('[data-goal-revealed]');

describe('adding a candidate with its work package', () => {
  it('offers no package and the packages of its project in their order; one click places it there, Undo takes it back', async () => {
    const sent = serve();
    renderGoal();

    fireEvent.click(await screen.findByRole('button', { name: 'Add: Release notes' }));
    expect(screen.getAllByRole('menuitem').map((entry) => entry.textContent)).toEqual(['No work package', 'Repo clean', 'Getting started']);
    fireEvent.click(screen.getByRole('menuitem', { name: 'Getting started' }));

    await waitFor(() => expect(sent).toEqual([
      { method: 'PATCH', path: '/api/v1/todos/c1/planning', body: { goalId: 'g1', workPackageId: 'w2' } },
    ]));
    fireEvent.click(await toastButton('Added: AutoPilot · Getting started', 'Undo'));
    await waitFor(() => expect(sent.at(-1)).toEqual({ method: 'PATCH', path: '/api/v1/todos/c1/planning', body: { goalId: '' } }));
    expect(await screen.findByText('Removed from the goal again')).toBeInTheDocument();
  });

  it('a candidate from a project without packages is added with one click, as before', async () => {
    const sent = serve();
    renderGoal();

    const add = await screen.findByRole('button', { name: 'Add: Backup rotation' });
    expect(add).not.toHaveAttribute('aria-haspopup');
    fireEvent.click(add);
    await waitFor(() => expect(sent).toEqual([{ method: 'POST', path: '/api/v1/goals/g1/todos', body: { todoIds: ['c4'] } }]));
    expect(await screen.findByText('Added: Homelab · No work package')).toBeInTheDocument();
  });
});

describe('“Show” after adding', () => {
  it('in the outline opens the folded package, scrolls there and highlights the TODO', async () => {
    localStorage.setItem('mysuricates-goal-outline', JSON.stringify({ g1: { 'wp:w1': false } }));
    serve();
    renderGoal();

    const toggle = await screen.findByRole('button', { name: 'Expand or collapse: Repo clean' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(await screen.findByRole('button', { name: 'Add: Release notes' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Repo clean' }));
    fireEvent.click(await toastButton('Added: AutoPilot · Repo clean', 'Show'));

    await waitFor(() => expect(revealedRow()).not.toBeNull());
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(within(revealedRow()!).getByRole('button', { name: /^Open TODO: Release notes/ })).toBeVisible();
    expect(scrolled).toHaveBeenCalledWith({ block: 'center', inline: 'center', behavior: 'smooth' });
    expect(useGoalReveal.getState().request).toBeNull();

    // „Zeigen“ lässt den Toast stehen: nach dem Hinschauen lässt es sich noch zurücknehmen
    // (länger warten, als ein schließender Toast zum Ausblenden braucht)
    await act(() => new Promise((resolve) => setTimeout(resolve, 400)));
    fireEvent.click(await toastButton('Added: AutoPilot · Repo clean', 'Undo'));
    expect(await screen.findByText('Removed from the goal again')).toBeInTheDocument();
  });

  it('from the list switches to the outline, because the list has no place for it', async () => {
    view('list');
    serve();
    renderGoal();

    fireEvent.click(await screen.findByRole('button', { name: 'Add: Release notes' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'No work package' }));
    fireEvent.click(await toastButton('Added: AutoPilot · No work package', 'Show'));

    await waitFor(() => expect(screen.getByRole('tab', { name: 'Outline' })).toHaveAttribute('aria-selected', 'true'));
    await waitFor(() => expect(revealedRow()).not.toBeNull());
    expect(within(revealedRow()!).getByRole('button', { name: /^Open TODO: Release notes/ })).toBeInTheDocument();
    expect(localStorage.getItem('mysuricates-goal-view-v2')).toBe('outline');
  });

  it('in the timeline opens the folded row of TODOs without a date and highlights the new one', async () => {
    view('timeline');
    localStorage.setItem('mysuricates-goal-undated', JSON.stringify({ g1: 'none' }));
    serve();
    renderGoal();

    fireEvent.click(await screen.findByRole('button', { name: 'Add: Release notes' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Repo clean' }));
    const show = await toastButton('Added: AutoPilot · Repo clean', 'Show');
    const row = await screen.findByTestId('timeline-row-p1-w1');
    await waitFor(() => expect(within(row).getByRole('button', { name: '2 TODOs without a date' })).toHaveAttribute('aria-expanded', 'false'));
    fireEvent.click(show);

    await waitFor(() => expect(revealedRow()).not.toBeNull());
    expect(within(row).getByRole('button', { name: '2 TODOs without a date' })).toHaveAttribute('aria-expanded', 'true');
    expect(within(revealedRow()!).getByRole('button', { name: 'Release notes – no due date' })).toBeInTheDocument();
  });

  it('in the timeline opens a package without a time span in its tray', async () => {
    view('timeline');
    serve();
    renderGoal();

    fireEvent.click(await screen.findByRole('button', { name: 'Add: Release notes' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Getting started' }));
    const show = await toastButton('Added: AutoPilot · Getting started', 'Show');
    const tray = screen.getByTestId('timeline-tray-p1');
    await waitFor(() => expect(within(tray).getByRole('button', { name: '1 TODO without a date' })).toHaveAttribute('aria-expanded', 'false'));
    fireEvent.click(show);

    await waitFor(() => expect(revealedRow()).not.toBeNull());
    expect(within(tray).getByRole('button', { name: '1 TODO without a date' })).toHaveAttribute('aria-expanded', 'true');
    expect(within(revealedRow()!).getByRole('button', { name: 'Release notes – no due date' })).toBeInTheDocument();
  });
});

describe('“+ TODO” at a work package', () => {
  it('finds TODOs without a goal from the package\'s project; Enter takes the first hit', async () => {
    const sent = serve();
    renderGoal();

    fireEvent.click(await screen.findByRole('button', { name: 'Add a TODO to “Repo clean”' }));
    const adder = within(screen.getByTestId('package-todo-adder-w1'));
    const input = adder.getByRole('combobox', { name: 'Find a TODO without a goal or type a new one' });
    expect(input).toHaveFocus();
    fireEvent.change(input, { target: { value: 'Rel' } });
    // „Relaunch site“ gehört zu Site – nicht zum Projekt des Pakets
    await waitFor(() => expect(adder.getAllByRole('option').map((option) => option.textContent))
      .toEqual(['Release notes', 'Create new: “Rel”']));
    expect(adder.getByRole('option', { name: 'Release notes' })).toHaveAttribute('aria-selected', 'true');
    fireEvent.keyDown(input, { key: 'Enter' });

    await waitFor(() => expect(sent).toEqual([
      { method: 'PATCH', path: '/api/v1/todos/c1/planning', body: { goalId: 'g1', workPackageId: 'w1' } },
    ]));
    expect(await screen.findByText('Added: AutoPilot · Repo clean')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId('package-todo-adder-w1')).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Add a TODO to “Repo clean”' })).toHaveFocus();
  });

  // Review 85889063: Rückgängig löscht nie – vom Ziel lösen und archivieren, auch wenn inzwischen ein Trigger lief
  it('creates a new TODO in the package\'s project when nothing matches; Undo archives it instead of deleting', async () => {
    const sent = serve();
    renderGoal();

    fireEvent.click(await screen.findByRole('button', { name: 'Add a TODO to “Repo clean”' }));
    const adder = within(screen.getByTestId('package-todo-adder-w1'));
    const input = adder.getByRole('combobox');
    fireEvent.change(input, { target: { value: 'Brand new thing' } });
    await screen.findByText('No matching TODO without a goal');
    expect(adder.getAllByRole('option').map((option) => option.textContent)).toEqual(['Create new: “Brand new thing”']);
    fireEvent.keyDown(input, { key: 'Enter' });

    await waitFor(() => expect(sent).toEqual([
      { method: 'POST', path: '/api/v1/todos', body: { title: 'Brand new thing', projectId: 'p1' } },
      { method: 'PATCH', path: '/api/v1/todos/n1/planning', body: { goalId: 'g1', workPackageId: 'w1' } },
    ]));
    fireEvent.click(await toastButton('Added: AutoPilot · Repo clean', 'Undo'));
    expect(await screen.findByText('Removed from the goal and archived')).toBeInTheDocument();
    expect(sent.slice(2)).toEqual([
      { method: 'PATCH', path: '/api/v1/todos/n1/planning', body: { goalId: '' } },
      { method: 'PUT', path: '/api/v1/todos/n1', body: { status: 'ARCHIVED' } },
    ]);
    expect(sent.some((entry) => entry.method === 'DELETE')).toBe(false);
  });

  it('arrow keys choose among the hits and “create new”; Escape closes', async () => {
    const sent = serve();
    renderGoal();

    fireEvent.click(await screen.findByRole('button', { name: 'Add a TODO to “Repo clean”' }));
    const adder = within(screen.getByTestId('package-todo-adder-w1'));
    const input = adder.getByRole('combobox');
    fireEvent.change(input, { target: { value: 'Release' } });
    await waitFor(() => expect(adder.getAllByRole('option')).toHaveLength(2));
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(adder.getByRole('option', { name: 'Create new: “Release”' })).toHaveAttribute('aria-selected', 'true');
    expect(input).toHaveAttribute('aria-activedescendant', adder.getByRole('option', { name: 'Create new: “Release”' }).id);
    fireEvent.keyDown(input, { key: 'Escape' });
    expect(screen.queryByTestId('package-todo-adder-w1')).not.toBeInTheDocument();
    expect(sent).toEqual([]);
  });

  it('also sits in the side panel of the package', async () => {
    const sent = serve();
    renderGoal('/goals/g1?item=workpackage:w2');

    const panel = await screen.findByTestId('goal-side-panel');
    fireEvent.click(within(panel).getByRole('button', { name: 'Add a TODO to “Getting started”' }));
    const input = within(panel).getByRole('combobox');
    fireEvent.change(input, { target: { value: 'Release' } });
    await waitFor(() => expect(within(panel).getAllByRole('option')).toHaveLength(2));
    fireEvent.click(within(panel).getByRole('option', { name: 'Release notes' }));

    await waitFor(() => expect(sent).toEqual([
      { method: 'PATCH', path: '/api/v1/todos/c1/planning', body: { goalId: 'g1', workPackageId: 'w2' } },
    ]));
    await waitFor(() => expect(within(panel).getByRole('button', { name: 'Add a TODO to “Getting started”' })).toHaveFocus());
  });
});
