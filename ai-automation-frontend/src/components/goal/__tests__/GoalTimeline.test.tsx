import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { DndContextProps, DragEndEvent } from '@dnd-kit/core';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { GoalTimeline } from '../GoalTimeline';
import { FALLBACK_WIDTH, NO_MILESTONE, axisFor, dayOf, dayOfIsoDate, xOfDay } from '../timelineLayout';
import type { Goal, GoalMilestone, GoalTodoItem } from '../../../types';

// Ziehen lässt sich in jsdom nicht echt ausführen (keine Layout-Maße). Der Test greift deshalb die
// Handler des DndContext ab und löst das Ende eines Zugs so aus, wie dnd-kit es melden würde.
const dnd = vi.hoisted(() => ({ props: null as DndContextProps | null }));
vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>();
  return {
    ...actual,
    DndContext: (props: DndContextProps) => {
      dnd.props = props;
      return <actual.DndContext {...props} />;
    },
  };
});

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  dnd.props = null;
});

const NOW = new Date(2026, 8, 24, 10, 0, 0);
const endOf = (iso: string) => new Date(`${iso}T23:59:59`).toISOString();

function item(overrides: Partial<GoalTodoItem>): GoalTodoItem {
  return {
    id: 'x', title: 'X', status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
    goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null,
    openQuestion: null, updatedAt: '2026-09-23T00:00:00Z', dueDate: null, milestoneId: null, ...overrides,
  };
}

const goal: Goal = {
  id: 'g1', title: 'Open-Source-Launch', description: null, status: 'ACTIVE', targetDate: endOf('2026-10-15'),
  createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
  progress: { total: 4, open: 3, inProgress: 0, backlog: 0, done: 1, openQuestions: 0, percentDone: 25 },
  guard: { enabled: false, workflowId: null, scheduleId: null, nextRunAt: null },
};

const beta: GoalMilestone = {
  id: 'm-beta', goalId: 'g1', title: 'Beta', dueDate: endOf('2026-10-01'), state: 'OPEN', total: 1, done: 0,
  reachedAt: null, reopened: false, createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
};

const todos: GoalTodoItem[] = [
  item({ id: 'a', title: 'Privacy findings', dueDate: endOf('2026-09-30'), milestoneId: 'm-beta' }),
  item({ id: 'b', title: 'Style probe', dueDate: endOf('2026-10-05') }),
  item({ id: 'c', title: 'Examples page' }),
  item({ id: 'd', title: 'Old banner', dueDate: endOf('2026-09-20') }),
  item({ id: 'e', title: 'Release notes', status: 'DONE', dueDate: endOf('2026-09-22') }),
];

interface Sent { method: string; path: string; body: unknown }

/** Nimmt alle schreibenden Aufrufe des Zeitstrahls entgegen. */
function recordWrites(): Sent[] {
  const sent: Sent[] = [];
  const record = async ({ request }: { request: Request }) => {
    const body = request.method === 'DELETE' ? null : await request.json();
    sent.push({ method: request.method, path: new URL(request.url).pathname, body });
    if (request.method === 'DELETE') return new HttpResponse(null, { status: 204 });
    return HttpResponse.json(request.url.includes('/milestones') ? { ...beta, ...(body as object) } : { id: 'x' });
  };
  server.use(
    http.put('/api/v1/todos/:id', record),
    http.patch('/api/v1/todos/:id/planning', record),
    http.post('/api/v1/goals/g1/milestones', record),
    http.put('/api/v1/goals/g1/milestones/:mid', record),
    http.delete('/api/v1/goals/g1/milestones/:mid', record),
  );
  return sent;
}

function renderTimeline(onOpenTodo = vi.fn()) {
  renderWithProviders(<GoalTimeline goal={goal} todos={todos} milestones={[beta]} onOpenTodo={onOpenTodo} now={NOW} />);
  return onOpenTodo;
}

/** Wo der Tag im Zeitstrahl liegt – dieselbe Achse, die der Zeitstrahl ohne gemessene Breite zeichnet. */
function xFor(iso: string): number {
  const axis = axisFor({ todos, milestones: [beta], targetDate: goal.targetDate, today: dayOf(NOW) }, FALLBACK_WIDTH);
  return xOfDay(axis, dayOfIsoDate(iso)!, FALLBACK_WIDTH);
}

/** Das Ende eines Zugs: TODO `todoId` losgelassen über `overId`, Zeiger `x` Pixel rechts vom Anfang der Zeile. */
function dropOn(todoId: string, overId: string | null, x = 0) {
  const rowLeft = 180;
  const event = {
    active: { id: `todo:${todoId}`, data: { current: undefined }, rect: { current: { initial: null, translated: null } } },
    over: overId === null ? null : {
      id: overId, data: { current: undefined }, disabled: false,
      rect: { left: rowLeft, top: 300, width: FALLBACK_WIDTH, height: 48, right: rowLeft + FALLBACK_WIDTH, bottom: 348 },
    },
    delta: { x: 40, y: 25 },
    activatorEvent: new MouseEvent('pointerdown', { clientX: rowLeft + x - 40, clientY: 280 }),
    collisions: null,
  } as unknown as DragEndEvent;
  act(() => dnd.props!.onDragEnd!(event));
}

describe('GoalTimeline', () => {
  it('shows a row per milestone and one without, the dated TODOs in them and the undated ones as unplanned', () => {
    renderTimeline();

    const betaRow = screen.getByTestId('timeline-row-m-beta');
    expect(within(betaRow).getByRole('button', { name: /^Privacy findings/ })).toBeInTheDocument();
    const noneRow = screen.getByTestId(`timeline-row-${NO_MILESTONE}`);
    expect(within(noneRow).getByRole('button', { name: /^Style probe/ })).toBeInTheDocument();
    expect(within(noneRow).queryByRole('button', { name: /^Examples page/ })).not.toBeInTheDocument();

    const unplanned = screen.getByRole('region', { name: 'Unplanned' });
    expect(within(unplanned).getByRole('button', { name: /^Examples page/ })).toBeInTheDocument();

    // überfällig rot, erledigt durchgestrichen
    expect(screen.getByRole('button', { name: /^Old banner/ })).toHaveAccessibleName(/overdue/i);
    expect(screen.getByRole('button', { name: /^Old banner/ })).toHaveAttribute('data-overdue', 'true');
    expect(screen.getByRole('button', { name: /^Release notes/ })).toHaveAttribute('data-done', 'true');
    expect(screen.getByRole('button', { name: /^Release notes/ })).not.toHaveAttribute('data-overdue', 'true');

    // Achse: Raute je Meilenstein, Fahne für das Zieldatum, heute
    expect(screen.getByRole('button', { name: /^Milestone Beta/ })).toBeInTheDocument();
    expect(within(screen.getByTestId('timeline-axis')).getByText('Today')).toBeInTheDocument();
    expect(screen.getByTestId('timeline-target')).toBeInTheDocument();
  });

  it('a drop on another day sets the due date at 23:59:59 local time', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('b', `row:${NO_MILESTONE}`, xFor('2026-10-07'));

    await waitFor(() => expect(sent).toEqual([
      { method: 'PUT', path: '/api/v1/todos/b', body: { dueDate: endOf('2026-10-07') } },
    ]));
  });

  it('a drop into a milestone row sets the milestone, keeping the date', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('b', 'row:m-beta', xFor('2026-10-05'));

    await waitFor(() => expect(sent).toEqual([
      { method: 'PATCH', path: '/api/v1/todos/b/planning', body: { milestoneId: 'm-beta' } },
    ]));
  });

  it('an unplanned TODO dropped into a milestone row gets date and milestone', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('c', 'row:m-beta', xFor('2026-09-29'));

    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent).toContainEqual({ method: 'PUT', path: '/api/v1/todos/c', body: { dueDate: endOf('2026-09-29') } });
    expect(sent).toContainEqual({ method: 'PATCH', path: '/api/v1/todos/c/planning', body: { milestoneId: 'm-beta' } });
  });

  it('back into "unplanned" clears the date; a drop outside or on the same spot changes nothing', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('a', null);
    dropOn('b', `row:${NO_MILESTONE}`, xFor('2026-10-05'));
    dropOn('a', 'unplanned');

    await waitFor(() => expect(sent).toEqual([
      { method: 'PUT', path: '/api/v1/todos/a', body: { dueDate: '' } },
    ]));
  });

  it('plans a TODO from its menu, without dragging', async () => {
    const sent = recordWrites();
    const onOpenTodo = renderTimeline();

    fireEvent.click(screen.getByRole('button', { name: /^Examples page/ }));
    const menu = screen.getByRole('dialog', { name: 'Plan: Examples page' });
    fireEvent.change(within(menu).getByLabelText('Set date'), { target: { value: '2026-10-02' } });
    fireEvent.click(within(menu).getByRole('button', { name: 'Set' }));
    await waitFor(() => expect(sent).toEqual([
      { method: 'PUT', path: '/api/v1/todos/c', body: { dueDate: endOf('2026-10-02') } },
    ]));

    fireEvent.click(screen.getByRole('button', { name: /^Privacy findings/ }));
    const second = screen.getByRole('dialog', { name: 'Plan: Privacy findings' });
    expect(within(second).getByLabelText('Set date')).toHaveValue('2026-09-30');
    expect(within(second).getByLabelText('Milestone')).toHaveValue('m-beta');
    fireEvent.change(within(second).getByLabelText('Milestone'), { target: { value: '' } });
    await waitFor(() => expect(sent).toContainEqual(
      { method: 'PATCH', path: '/api/v1/todos/a/planning', body: { milestoneId: '' } },
    ));

    fireEvent.click(screen.getByRole('button', { name: /^Style probe/ }));
    const third = screen.getByRole('dialog', { name: 'Plan: Style probe' });
    fireEvent.click(within(third).getByRole('button', { name: 'Clear date' }));
    await waitFor(() => expect(sent).toContainEqual({ method: 'PUT', path: '/api/v1/todos/b', body: { dueDate: '' } }));

    fireEvent.click(screen.getByRole('button', { name: /^Style probe/ }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Plan: Style probe' })).getByRole('button', { name: 'Edit' }));
    expect(onOpenTodo).toHaveBeenCalledWith('b');
  });

  it('closes the menu with Escape', () => {
    renderTimeline();

    fireEvent.click(screen.getByRole('button', { name: /^Examples page/ }));
    fireEvent.keyDown(screen.getByRole('dialog', { name: 'Plan: Examples page' }), { key: 'Escape' });

    expect(screen.queryByRole('dialog', { name: 'Plan: Examples page' })).not.toBeInTheDocument();
  });

  it('adds a milestone from the button and by a click on the axis', async () => {
    const sent = recordWrites();
    renderTimeline();

    fireEvent.click(screen.getByRole('button', { name: 'Milestone' }));
    const form = screen.getByRole('dialog', { name: 'New milestone' });
    fireEvent.change(within(form).getByLabelText('Title'), { target: { value: 'RC' } });
    fireEvent.change(within(form).getByLabelText('Date'), { target: { value: '2026-10-08' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent).toEqual([
      { method: 'POST', path: '/api/v1/goals/g1/milestones', body: { title: 'RC', dueDate: endOf('2026-10-08') } },
    ]));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New milestone' })).not.toBeInTheDocument());

    fireEvent.click(screen.getByTestId('timeline-axis'), { clientX: xFor('2026-10-12') });
    expect(within(screen.getByRole('dialog', { name: 'New milestone' })).getByLabelText('Date')).toHaveValue('2026-10-12');
  });

  it('a diamond opens the milestone card with its TODOs, to edit or delete it', async () => {
    const sent = recordWrites();
    renderTimeline();

    fireEvent.click(screen.getByRole('button', { name: /^Milestone Beta/ }));
    const card = screen.getByRole('dialog', { name: 'Milestone Beta' });
    expect(within(card).getByText('Privacy findings')).toBeInTheDocument();
    expect(within(card).getByText('0 of 1 done')).toBeInTheDocument();

    fireEvent.click(within(card).getByRole('button', { name: 'Edit' }));
    const form = screen.getByRole('dialog', { name: 'Edit milestone' });
    expect(within(form).getByLabelText('Date')).toHaveValue('2026-10-01');
    fireEvent.change(within(form).getByLabelText('Title'), { target: { value: 'Public beta' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent).toEqual([
      { method: 'PUT', path: '/api/v1/goals/g1/milestones/m-beta', body: { title: 'Public beta' } },
    ]));

    // 23635ef6: the timeline asks in its own dialog; the card closes, a click in the dialog must not matter to it
    fireEvent.click(screen.getByRole('button', { name: /^Milestone Beta/ }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Milestone Beta' })).getByRole('button', { name: 'Delete' }));
    expect(screen.queryByRole('dialog', { name: 'Milestone Beta' })).not.toBeInTheDocument();
    const question = screen.getByRole('dialog', { name: 'Delete milestone' });
    expect(within(question).getByText('Delete the milestone “Beta”? Its TODOs stay in the goal.')).toBeInTheDocument();
    fireEvent.click(within(question).getByRole('button', { name: 'Cancel' }));
    expect(sent.filter((call) => call.method === 'DELETE')).toEqual([]);

    fireEvent.click(screen.getByRole('button', { name: /^Milestone Beta/ }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Milestone Beta' })).getByRole('button', { name: 'Delete' }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Delete milestone' })).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(sent).toContainEqual({ method: 'DELETE', path: '/api/v1/goals/g1/milestones/m-beta', body: null }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Delete milestone' })).not.toBeInTheDocument());
  });
});
