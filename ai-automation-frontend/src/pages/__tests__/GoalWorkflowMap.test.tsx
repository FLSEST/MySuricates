import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { KeyboardSensor } from '@dnd-kit/core';
import type { DndContextProps, DragEndEvent, DragOverEvent, DragStartEvent } from '@dnd-kit/core';
import type { ReactNode } from 'react';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { useToastStore } from '../../stores/toastStore';
import { ToastContainer } from '../../components/ui/ToastContainer';
import { GoalDetail } from '../GoalDetail';
import { targetCycleCoordinates } from '../../components/goal/workflowMap';
import type { GoalDetail as GoalDetailData, GoalRelations, GoalTodoItem, GoalTodoRelation } from '../../types';

// 52bc1e8c (Flo): „Ist ja das Map ein map to workflow, oder? Das sollte dann so heißen und auch ganz rechts stehen …
// dass man den Workflow auch ziehen kann, dass der auf der rechten Seite ziehbar wäre?“ – „ja passt“.
// Ziehen lässt sich in jsdom nicht echt ausführen: der Test greift die Handler des DndContext ab und löst
// Beginn, Bewegung und Ende so aus, wie dnd-kit sie meldet; das Bild unter dem Zeiger steht immer.
const dnd = vi.hoisted(() => ({ props: null as DndContextProps | null }));
vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>();
  return {
    ...actual,
    DndContext: (props: DndContextProps) => {
      dnd.props = props;
      return <actual.DndContext {...props} />;
    },
    DragOverlay: ({ children }: { children?: ReactNode }) => <div data-testid="drag-overlay">{children}</div>,
  };
});

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  localStorage.setItem('mysuricates-goal-view-v2', 'map');
  useI18nStore.setState({ locale: 'en' });
  useToastStore.setState({ toasts: [] });
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1440 });
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: 900 });
});

function item(overrides: Partial<GoalTodoItem>): GoalTodoItem {
  return {
    id: 'x', title: 'X', status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [], goalRank: null,
    complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null, openQuestion: null,
    updatedAt: '', dueDate: null, workPackageId: null, decision: null, decidedAt: null, decidedBy: null, decisionState: 'NONE', ...overrides,
  };
}

const detail: GoalDetailData = {
  goal: {
    id: 'g1', title: 'Release', description: null, status: 'ACTIVE', targetDate: null, createdAt: '', updatedAt: '',
    progress: { total: 3, open: 3, inProgress: 0, backlog: 0, done: 0, openQuestions: 0, decided: 0, percentDone: 0 },
    guard: { enabled: false, workflowId: null, scheduleId: null, nextRunAt: null },
  },
  todos: [
    item({ id: 'a', title: 'Licence files', workPackageId: 'w1' }),
    item({ id: 'b', title: 'Banner', workPackageId: 'w1' }),
    item({ id: 'c', title: 'Changelog' }),
  ],
  byProject: [{ projectId: 'p1', projectName: 'AutoPilot', total: 3, done: 0 }],
  projects: [{ projectId: 'p1', name: 'AutoPilot', archived: false, position: 0, total: 3, done: 0, openQuestions: 0, decided: 0 }],
  workPackages: [{
    id: 'w1', goalId: 'g1', title: 'Repo clean', projectId: 'p1', startDate: null, endDate: null, position: 0, state: 'OPEN',
    total: 2, done: 0, openQuestions: 0, decided: 0, completedAt: null, reopened: false, createdAt: '', updatedAt: '',
  }],
  milestones: [],
};

function relation(todo: GoalTodoItem, workers: GoalTodoRelation['workers'] = []): GoalTodoRelation {
  return {
    id: todo.id, title: todo.title, status: todo.status, priority: todo.priority, projectId: todo.projectId, projectName: todo.projectName,
    tags: [], goalRank: null, candidate: false,
    origin: { kind: 'USER', workflowId: null, workflowName: null, runId: null, runStatus: null, findingIds: [], agent: null },
    workers, result: null,
  };
}

const linkedFix = { kind: 'LINKED' as const, workflowId: 'wf-old', workflowName: 'Old fixer', triggerId: null, triggerLabel: null };
const relations: GoalRelations = {
  goalId: 'g1', activeTriggers: 0,
  todos: [relation(detail.todos[0]!), relation(detail.todos[1]!, [linkedFix]), relation(detail.todos[2]!)],
};

interface Sent { method: string; path: string; body: unknown }

function serve({ workflowsEndpoint = true, attachments = [] as unknown[], hold = null as Promise<void> | null } = {}) {
  const sent: Sent[] = [];
  // der Server merkt sich die Verknüpfung, damit das Nachladen nach dem Ablegen sie auch zeigt
  const linked = new Map<string, string>();
  const names: Record<string, string> = { 'wf-fix': 'Fix bot', 'wf-site': 'Site build' };
  const current = (): GoalRelations => ({
    ...relations,
    todos: relations.todos.map((todo) => {
      const id = linked.get(todo.id);
      if (id === undefined) return todo;
      const others = todo.workers.filter((worker) => worker.kind !== 'LINKED');
      return { ...todo, workers: id ? [{ ...linkedFix, workflowId: id, workflowName: names[id]! }, ...others] : others };
    }),
  });
  const record = async ({ request }: { request: Request }) => {
    const body = request.method === 'DELETE' ? null : await request.json();
    const path = new URL(request.url).pathname;
    sent.push({ method: request.method, path, body });
    if (hold) await hold;
    if (request.method === 'PUT') linked.set(path.split('/').pop()!, (body as { linkedWorkflowId: string }).linkedWorkflowId);
    if (request.method === 'DELETE') return new HttpResponse(null, { status: 204 });
    if (request.url.includes('/goals/g1/workflows')) return HttpResponse.json({ id: 'att-new', ...(body as object) }, { status: 201 });
    return HttpResponse.json({ id: 'a' });
  };
  server.use(
    http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)),
    http.get('/api/v1/goals/g1/relations', () => HttpResponse.json(current())),
    http.get('/api/v1/goals/g1/workflows', () => (workflowsEndpoint
      ? HttpResponse.json(attachments) : HttpResponse.json({ error: 'No static resource' }, { status: 404 }))),
    http.get('/api/v1/workflows', () => HttpResponse.json([
      { id: 'wf-fix', projectId: 'p1', projectName: 'AutoPilot', name: 'Fix bot', blocks: [], createdAt: '', updatedAt: '' },
      { id: 'wf-site', projectId: 'p9', projectName: 'Website', name: 'Site build', blocks: [], createdAt: '', updatedAt: '' },
    ])),
    http.put('/api/v1/todos/:id', record),
    http.post('/api/v1/goals/g1/workflows', record),
    http.delete('/api/v1/goals/g1/workflows/:id', record),
  );
  return sent;
}

function renderGoal() {
  renderWithProviders(
    <>
      <Routes><Route path="/goals/:goalId" element={<GoalDetail />} /></Routes>
      <ToastContainer />
    </>,
    { route: '/goals/g1' },
  );
}

const workflow = (id: string) => {
  const [name, projectId, projectName] = id === 'wf-fix' ? ['Fix bot', 'p1', 'AutoPilot'] : ['Site build', 'p9', 'Website'];
  return { id, name, projectId, projectName };
};
const active = (id: string) => ({ id: `wm-wf:${id}`, data: { current: { workflow: workflow(id) } }, rect: { current: { initial: null, translated: null } } });
const startDrag = (id: string) => act(() => dnd.props!.onDragStart!({ active: active(id) } as unknown as DragStartEvent));
const moveOver = (id: string, over: string | null) =>
  act(() => dnd.props!.onDragOver!({ active: active(id), over: over ? { id: over } : null } as unknown as DragOverEvent));
const drop = (id: string, over: string | null) =>
  act(() => dnd.props!.onDragEnd!({ active: active(id), over: over ? { id: over } : null } as unknown as DragEndEvent));
const live = () => screen.getByTestId('workflow-map-live').textContent;

describe('Workflow map', () => {
  it('is called "Workflow map" and stands rightmost; the old saved value "map" still opens it', async () => {
    serve();
    renderGoal();

    const tabs = (await screen.findAllByRole('tab')).map((tab) => tab.textContent);
    expect(tabs).toEqual(['Outline', 'List', 'Timeline', 'Workflow map']);
    expect(screen.getByRole('tab', { name: 'Workflow map' })).toHaveAttribute('aria-selected', 'true');
  });

  it('groups the map by work package and offers the workflows of the goal\'s projects in the panel', async () => {
    serve();
    renderGoal();

    await screen.findByTestId('goal-relation-map');
    await waitFor(() => expect(screen.getAllByTestId('map-group-label').map((label) => label.textContent))
      .toEqual(['AutoPilot · Repo clean', 'AutoPilot · No work package']));
    const panel = await screen.findByTestId('workflow-map-panel');
    expect(await within(panel).findByRole('button', { name: 'Drag workflow Fix bot' })).toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: 'Drag workflow Site build' })).not.toBeInTheDocument();
    fireEvent.click(within(panel).getByRole('switch', { name: 'Show all workflows' }));
    expect(within(panel).getByRole('button', { name: 'Drag workflow Site build' })).toBeInTheDocument();

    // closed, a button brings it back
    fireEvent.click(within(panel).getByRole('button', { name: 'Close the workflow panel' }));
    expect(screen.queryByTestId('workflow-map-panel')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('workflow-map-open'));
    expect(screen.getByTestId('workflow-map-panel')).toBeInTheDocument();
  });

  it('links a workflow to a TODO by dropping it there: only linkedWorkflowId is sent, the map shows it at once, Undo takes it back', async () => {
    const sent = serve();
    renderGoal();
    await screen.findByRole('button', { name: 'Drag workflow Fix bot' });

    startDrag('wf-fix');
    moveOver('wf-fix', 'wm-todo:a');
    expect(live()).toBe('→ works on: Licence files');
    // only the targets that fit light up
    expect(document.querySelector('[data-drop-target="wm-todo:a"]')).toHaveAttribute('data-accepts', 'true');
    drop('wf-fix', 'wm-todo:a');

    await waitFor(() => expect(sent).toEqual([{ method: 'PUT', path: '/api/v1/todos/a', body: { linkedWorkflowId: 'wf-fix' } }]));
    expect(document.querySelector('[data-node="worker:wf-fix"]')).toBeInTheDocument();
    const toast = await screen.findByText('Fix bot now works on “Licence files”');
    fireEvent.click(within(toast.closest('[data-testid="toast"]') as HTMLElement).getByRole('button', { name: 'Undo' }));
    await waitFor(() => expect(sent).toContainEqual({ method: 'PUT', path: '/api/v1/todos/a', body: { linkedWorkflowId: '' } }));
    expect(await screen.findByText('Assignment taken back')).toBeInTheDocument();
  });

  it('can be picked up with the keyboard: Space lifts, arrows jump between targets, Escape puts it back', async () => {
    const sent = serve();
    renderGoal();
    const item = await screen.findByRole('button', { name: 'Drag workflow Fix bot' });
    expect(item).toHaveAttribute('aria-roledescription', 'draggable');
    const keyboard = dnd.props!.sensors!.find((sensor) => sensor.sensor === KeyboardSensor);
    expect(keyboard?.options).toMatchObject({ coordinateGetter: targetCycleCoordinates, keyboardCodes: { start: ['Space'], end: ['Enter', 'Space'] } });

    item.focus();
    fireEvent.keyDown(item, { code: 'Space', key: ' ' });
    expect(await screen.findByTestId('workflow-map-overlay')).toHaveTextContent('Fix bot');
    // unterwegs ist das TODO als passendes Ziel markiert
    expect(document.querySelector('[data-drop-target="wm-todo:a"]')).toHaveAttribute('data-accepts', 'true');
    fireEvent.keyDown(document, { code: 'Escape', key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('workflow-map-overlay')).not.toBeInTheDocument());
    expect(sent).toEqual([]);
  });

  it('shows the link on the map before the server has answered', async () => {
    let release = () => {};
    const sent = serve({ hold: new Promise<void>((resolve) => { release = resolve; }) });
    renderGoal();
    await screen.findByRole('button', { name: 'Drag workflow Fix bot' });
    expect(document.querySelector('[data-node="worker:wf-fix"]')).not.toBeInTheDocument();

    startDrag('wf-fix');
    drop('wf-fix', 'wm-todo:c');
    await waitFor(() => expect(document.querySelector('[data-node="worker:wf-fix"]')).toBeInTheDocument());
    expect(sent).toHaveLength(1);
    expect(screen.queryByText('Fix bot now works on “Changelog”')).not.toBeInTheDocument();
    release();
    expect(await screen.findByText('Fix bot now works on “Changelog”')).toBeInTheDocument();
  });

  it('asks before replacing a workflow a TODO already has, and names the project of a foreign workflow', async () => {
    const sent = serve();
    renderGoal();
    await screen.findByRole('button', { name: 'Drag workflow Fix bot' });

    startDrag('wf-site');
    moveOver('wf-site', 'wm-todo:b');
    expect(live()).toBe('→ works on: Banner (workflow from Website)');
    drop('wf-site', 'wm-todo:b');
    const question = screen.getByRole('dialog', { name: 'Replace the workflow?' });
    expect(question).toHaveTextContent('“Banner” is already worked on by Old fixer. Replace it with Site build?');
    expect(sent).toEqual([]);
    fireEvent.click(within(question).getByRole('button', { name: 'Replace' }));
    await waitFor(() => expect(sent).toEqual([{ method: 'PUT', path: '/api/v1/todos/b', body: { linkedWorkflowId: 'wf-site' } }]));
  });

  it('assigns to a work package and to the whole goal as a worker; Undo removes it; twice is only a hint', async () => {
    const sent = serve({ attachments: [{ id: 'att-1', goalId: 'g1', workPackageId: null, workflowId: 'wf-site', workflowName: 'Site build', workflowProjectId: 'p9', role: 'WORKER', guard: false }] });
    renderGoal();
    await screen.findByRole('button', { name: 'Drag workflow Fix bot' });
    await screen.findByText('Whole goal: Release');

    startDrag('wf-fix');
    moveOver('wf-fix', 'wm-pkg:w1');
    expect(live()).toBe('→ work package Repo clean');
    drop('wf-fix', 'wm-pkg:w1');
    await waitFor(() => expect(sent).toEqual([
      { method: 'POST', path: '/api/v1/goals/g1/workflows', body: { workflowId: 'wf-fix', role: 'WORKER', workPackageId: 'w1' } },
    ]));
    const toast = await screen.findByText('Fix bot assigned to the work package Repo clean');
    fireEvent.click(within(toast.closest('[data-testid="toast"]') as HTMLElement).getByRole('button', { name: 'Undo' }));
    await waitFor(() => expect(sent).toContainEqual({ method: 'DELETE', path: '/api/v1/goals/g1/workflows/att-new', body: null }));

    startDrag('wf-fix');
    moveOver('wf-fix', 'wm-goal');
    expect(live()).toBe('→ goal Release');
    drop('wf-fix', 'wm-goal');
    await waitFor(() => expect(sent).toContainEqual({ method: 'POST', path: '/api/v1/goals/g1/workflows', body: { workflowId: 'wf-fix', role: 'WORKER' } }));

    // Site build hangs on the goal already: nothing is sent, a hint says so
    const before = sent.length;
    startDrag('wf-site');
    moveOver('wf-site', 'wm-goal');
    expect(live()).toBe('Already there');
    drop('wf-site', 'wm-goal');
    expect(await screen.findByText('Already there', { selector: '[data-testid="toast"] *' })).toBeInTheDocument();
    expect(sent).toHaveLength(before);
  });

  it('with an older backend (404 on /workflows) only links TODOs – no goal band, packages say why', async () => {
    const sent = serve({ workflowsEndpoint: false });
    renderGoal();
    await screen.findByRole('button', { name: 'Drag workflow Fix bot' });

    expect(screen.queryByText('Whole goal: Release')).not.toBeInTheDocument();
    startDrag('wf-fix');
    moveOver('wf-fix', 'wm-pkg:w1');
    expect(live()).toBe('Assigning becomes available after the backend restart');
    expect(screen.getByTestId('workflow-map-live')).toHaveAttribute('data-refused', 'true');
    drop('wf-fix', 'wm-pkg:w1');
    startDrag('wf-fix');
    drop('wf-fix', 'wm-todo:c');
    await waitFor(() => expect(sent).toEqual([{ method: 'PUT', path: '/api/v1/todos/c', body: { linkedWorkflowId: 'wf-fix' } }]));
  });
});
