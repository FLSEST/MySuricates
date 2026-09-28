import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { DndContextProps, DragEndEvent, DragMoveEvent, DragStartEvent } from '@dnd-kit/core';
import type { ReactNode } from 'react';
import { StrictMode, createRef } from 'react';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { GoalTimeline } from '../GoalTimeline';
import { DecisionContext } from '../decisionContext';
import { GoalWorkflowsContext } from '../goalWorkflowsContext';
import { TimelineAxisView } from '../TimelineAxisView';
import { NUDGE_DELAY } from '../useKeyboardNudge';
import {
  CHIP_ANCHOR, CHIP_WIDTH, FALLBACK_WIDTH, NO_PACKAGE, axisFor, dayOf, dayOfIsoDate, xOfDay, type TimelineAxis,
} from '../timelineLayout';
import type { Goal, GoalMilestone, GoalProject, GoalTodoItem, GoalWorkPackage } from '../../../types';

// Ziehen lässt sich in jsdom nicht echt ausführen (keine Layout-Maße). Der Test greift deshalb die
// Handler des DndContext ab und löst Beginn, Bewegung und Ende eines Zugs so aus, wie dnd-kit sie
// melden würde. Das Bild unter dem Zeiger (DragOverlay) zeigt dnd-kit nur bei einem echten Zug – hier
// steht es immer, damit die Live-Zeile zu sehen ist, sobald der Zeitstrahl einen Zug kennt.
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
afterEach(() => {
  server.resetHandlers();
  vi.useRealTimers();
});
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  dnd.props = null;
});

const NOW = new Date(2026, 8, 24, 10, 0, 0);
const endOf = (iso: string) => new Date(`${iso}T23:59:59`).toISOString();
const startOf = (iso: string) => new Date(`${iso}T00:00:00`).toISOString();
/** Leerzeichen, wie ICU sie in englische Zeiträume setzt, als gewöhnliche. */
const plain = (text: string | null | undefined) => (text ?? '').replace(/\s/g, ' ');

function item(overrides: Partial<GoalTodoItem>): GoalTodoItem {
  return {
    id: 'x', title: 'X', status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
    goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null,
    openQuestion: null, updatedAt: '2026-09-23T00:00:00Z', dueDate: null, workPackageId: null, ...overrides,
  };
}

function wp(id: string, title: string, span: [string, string] | null, overrides: Partial<GoalWorkPackage> = {}): GoalWorkPackage {
  return {
    id, goalId: 'g1', projectId: 'p1', title, startDate: span ? startOf(span[0]) : null, endDate: span ? endOf(span[1]) : null,
    position: 0, state: 'OPEN', total: 2, done: 0, openQuestions: 0, completedAt: null, reopened: false,
    createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z', ...overrides,
  };
}

const goal: Goal = {
  id: 'g1', title: 'Release', description: null, status: 'ACTIVE', targetDate: endOf('2026-10-15'),
  createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
  progress: { total: 8, open: 7, inProgress: 0, backlog: 0, done: 1, openQuestions: 0, percentDone: 12 },
  guard: { enabled: false, workflowId: null, scheduleId: null, nextRunAt: null },
};

const workPackages: GoalWorkPackage[] = [
  wp('w-umbau', 'Umbau', ['2026-09-21', '2026-09-30'], { position: 0 }),
  wp('w-repo', 'Repo sauber', ['2026-10-01', '2026-10-07'], { position: 1, total: 2 }),
  wp('w-docs', 'Handbook', null, { position: 2, total: 1 }),
  wp('w-site', 'Werbematerial', ['2026-10-05', '2026-10-10'], { projectId: 'p2', total: 1 }),
];

const milestones: GoalMilestone[] = [
  {
    id: 'm-freeze', goalId: 'g1', title: 'Code freeze', dueDate: endOf('2026-10-08'), state: 'OPEN',
    workPackageIds: ['w-umbau', 'w-repo'], packagesTotal: 2, packagesDone: 0, reachedAt: null, reopened: false,
    createdAt: '', updatedAt: '',
  },
  {
    id: 'm-launch', goalId: 'g1', title: 'Launch', dueDate: endOf('2026-10-14'), state: 'OPEN',
    workPackageIds: [], packagesTotal: 0, packagesDone: 0, reachedAt: null, reopened: false, createdAt: '', updatedAt: '',
  },
];

const projects: GoalProject[] = [
  { projectId: 'p1', name: 'AutoPilot', archived: false, position: 0, total: 7, done: 1, openQuestions: 0 },
  { projectId: 'p2', name: 'Site', archived: false, position: 1, total: 1, done: 0, openQuestions: 0 },
];

const todos: GoalTodoItem[] = [
  item({ id: 'a', title: 'Privacy findings', dueDate: endOf('2026-09-29'), workPackageId: 'w-umbau' }),
  item({ id: 'b', title: 'Style probe', workPackageId: 'w-umbau' }),
  item({ id: 'c', title: 'Examples page', dueDate: endOf('2026-10-02') }),
  item({ id: 'd', title: 'Old banner', dueDate: endOf('2026-09-20'), workPackageId: 'w-repo' }),
  item({ id: 'e', title: 'Release notes', status: 'DONE', dueDate: endOf('2026-09-22'), workPackageId: 'w-repo' }),
  item({ id: 'h', title: 'Handbook text', workPackageId: 'w-docs' }),
  item({ id: 'n', title: 'Stray note' }),
  item({ id: 's', title: 'Site copy', projectId: 'p2', projectName: 'Site', dueDate: endOf('2026-10-06'), workPackageId: 'w-site' }),
];

interface Sent { method: string; path: string; body: unknown }

/** Nimmt alle schreibenden Aufrufe des Zeitstrahls entgegen. */
function recordWrites(): Sent[] {
  const sent: Sent[] = [];
  const record = async ({ request }: { request: Request }) => {
    const body = request.method === 'DELETE' ? null : await request.json();
    sent.push({ method: request.method, path: new URL(request.url).pathname, body });
    if (request.method === 'DELETE') return new HttpResponse(null, { status: 204 });
    if (request.url.includes('/milestones')) return HttpResponse.json({ ...milestones[0], ...(body as object) });
    if (request.url.includes('/work-packages')) return HttpResponse.json({ ...workPackages[0], ...(body as object) });
    return HttpResponse.json({ id: 'x' });
  };
  server.use(
    http.put('/api/v1/todos/:id', record),
    http.patch('/api/v1/todos/:id/planning', record),
    http.post('/api/v1/goals/g1/work-packages', record),
    http.put('/api/v1/goals/g1/work-packages/:id', record),
    http.delete('/api/v1/goals/g1/work-packages/:id', record),
    http.post('/api/v1/goals/g1/milestones', record),
    http.put('/api/v1/goals/g1/milestones/:id', record),
    http.delete('/api/v1/goals/g1/milestones/:id', record),
  );
  return sent;
}

function renderTimeline(
  extra: { todos?: GoalTodoItem[]; legacy?: boolean; strict?: boolean; decisions?: boolean } = {}, onOpenTodo = vi.fn(),
) {
  let timeline = (
    <GoalTimeline goal={goal} todos={extra.todos ?? todos} workPackages={workPackages} milestones={extra.legacy ? [] : milestones}
      projects={projects} legacyBackend={extra.legacy} onOpenTodo={onOpenTodo} now={NOW} />
  );
  if (extra.decisions) timeline = <DecisionContext.Provider value={{ goalId: 'g1', enabled: true }}>{timeline}</DecisionContext.Provider>;
  renderWithProviders(extra.strict ? <StrictMode>{timeline}</StrictMode> : timeline);
  return onOpenTodo;
}

/** Dieselbe Achse, die der Zeitstrahl ohne gemessene Breite zeichnet. */
const axis = axisFor({ todos, workPackages, milestones, targetDate: goal.targetDate, today: dayOf(NOW) }, FALLBACK_WIDTH);
const PER_DAY = FALLBACK_WIDTH / axis.days;
/** Wo der Tag im Zeitstrahl liegt. */
const xFor = (iso: string) => xOfDay(axis, dayOfIsoDate(iso)!, FALLBACK_WIDTH);

const ROW_LEFT = 180;
/**
 * Ein Zug, wie dnd-kit ihn meldet: `active` (etwa `todo:a`, `wp:w-repo`, `wp-end:w-repo`, `ms:m-freeze`)
 * über `over`, der Zeiger `x` Pixel rechts vom Anfang der Fläche, um `deltaX` bewegt.
 */
function dragEvent(active: string, over: string | null, x = 0, deltaX = 40) {
  return {
    active: { id: active, data: { current: undefined }, rect: { current: { initial: null, translated: null } } },
    over: over === null ? null : {
      id: over, data: { current: undefined }, disabled: false,
      rect: { left: ROW_LEFT, top: 300, width: FALLBACK_WIDTH, height: 48, right: ROW_LEFT + FALLBACK_WIDTH, bottom: 348 },
    },
    delta: { x: deltaX, y: 25 },
    activatorEvent: new MouseEvent('pointerdown', { clientX: ROW_LEFT + x - deltaX, clientY: 280 }),
    collisions: null,
  };
}
const startDrag = (active: string) => act(() => dnd.props!.onDragStart!(dragEvent(active, null) as unknown as DragStartEvent));
const moveDrag = (active: string, over: string | null, x = 0, deltaX = 40) =>
  act(() => dnd.props!.onDragMove!(dragEvent(active, over, x, deltaX) as unknown as DragMoveEvent));
const dropOn = (active: string, over: string | null, x = 0, deltaX = 40) =>
  act(() => dnd.props!.onDragEnd!(dragEvent(active, over, x, deltaX) as unknown as DragEndEvent));

/** Das Ende eines Zugs per Tastatur: kein Zeiger, der Tag steht unter dem Statuspunkt des angehobenen Chips. */
function keyboardDropOn(todoId: string, overId: string, dotX: number) {
  const left = ROW_LEFT + dotX - CHIP_ANCHOR;
  const event = {
    active: {
      id: `todo:${todoId}`, data: { current: { anchor: CHIP_ANCHOR, flipped: false, kind: 'todo' } },
      rect: { current: { initial: null, translated: { left, top: 310, width: CHIP_WIDTH, height: 28, right: left + CHIP_WIDTH, bottom: 338 } } },
    },
    over: {
      id: overId, data: { current: undefined }, disabled: false,
      rect: { left: ROW_LEFT, top: 300, width: FALLBACK_WIDTH, height: 48, right: ROW_LEFT + FALLBACK_WIDTH, bottom: 348 },
    },
    delta: { x: 90, y: 0 },
    activatorEvent: new KeyboardEvent('keydown', { code: 'Space' }),
    collisions: null,
  } as unknown as DragEndEvent;
  act(() => dnd.props!.onDragEnd!(event));
}

describe('GoalTimeline (c60bf914): milestones, bands and bars', () => {
  it('shows the milestones on top, a band per project with a bar per scheduled package and the others in the tray', () => {
    renderTimeline();

    const row = screen.getByTestId('timeline-milestones');
    expect(within(row).getByRole('button', { name: /^Milestone Code freeze – open, 10\/8\/2026/ })).toHaveTextContent('Code freeze');
    expect(within(row).getByRole('button', { name: /^Milestone Launch/ })).toBeInTheDocument();
    // the milestone's line runs through all bands
    expect(screen.getAllByTestId('timeline-guide')).toHaveLength(2);

    const autopilot = screen.getByRole('region', { name: 'Project band AutoPilot' });
    const umbau = within(autopilot).getByTestId('timeline-bar-w-umbau');
    expect(umbau).toHaveAttribute('data-start', String(dayOfIsoDate('2026-09-21')));
    expect(umbau).toHaveAttribute('data-end', String(dayOfIsoDate('2026-09-30')));
    expect(plain(within(umbau).getByRole('button').getAttribute('aria-label'))).toBe('Work package Umbau, 9/21 – 9/30, 0 of 2 done');
    expect(within(autopilot).getByTestId('timeline-bar-w-repo')).toBeInTheDocument();
    // one row per package, the unscheduled one waits in the tray
    expect(within(autopilot).queryByTestId(`timeline-row-p1-w-docs`)).not.toBeInTheDocument();
    expect(within(screen.getByTestId('timeline-tray-p1')).getByRole('button', { name: 'Work package Handbook, no time span, 0 of 1 done' }))
      .toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Project band Site' })).getByTestId('timeline-bar-w-site')).toBeInTheDocument();
  });

  it('puts due TODOs under their bar and keeps the undated ones folded as a count at the label', () => {
    renderTimeline();

    const umbau = screen.getByTestId('timeline-row-p1-w-umbau');
    expect(within(umbau).getByRole('button', { name: /^Privacy findings – Due/ })).toBeInTheDocument();
    const toggle = within(umbau).getByRole('button', { name: '1 without a date' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(within(umbau).queryByRole('button', { name: /^Style probe/ })).not.toBeInTheDocument();
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(within(umbau).getByRole('button', { name: 'Style probe – no due date' })).toBeInTheDocument();

    // TODOs without a package: a row only because there are some
    const loose = screen.getByTestId(`timeline-row-p1-${NO_PACKAGE}`);
    expect(within(loose).getByRole('button', { name: /^Examples page/ })).toBeInTheDocument();
    expect(within(loose).getByRole('button', { name: '1 without a date' })).toBeInTheDocument();
    expect(screen.queryByTestId(`timeline-row-p2-${NO_PACKAGE}`)).not.toBeInTheDocument();

    // the tray opens the undated TODOs of its package, too
    fireEvent.click(within(screen.getByTestId('timeline-tray-p1')).getByRole('button', { name: '1 without a date' }));
    expect(screen.getByRole('button', { name: 'Handbook text – no due date' })).toBeInTheDocument();

    // overdue red, done struck through
    expect(screen.getByRole('button', { name: /^Old banner/ })).toHaveAttribute('data-overdue', 'true');
    expect(screen.getByRole('button', { name: /^Release notes/ })).toHaveAttribute('data-done', 'true');
  });

  it('shows only today to four weeks ahead when nothing is dated', () => {
    renderWithProviders(
      <GoalTimeline goal={{ ...goal, targetDate: null }} todos={[]} workPackages={[]} milestones={[]} projects={projects}
        onOpenTodo={vi.fn()} now={NOW} />,
    );
    expect(screen.getByTestId('timeline-axis')).toBeInTheDocument();
    expect(screen.queryByTestId('timeline-target')).not.toBeInTheDocument();
  });
});

describe('GoalTimeline — TODOs', () => {
  it('a drop into another package row of the same project sets package and day', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('todo:c', 'row:p1:w-repo', xFor('2026-10-05'));
    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent).toContainEqual({ method: 'PUT', path: '/api/v1/todos/c', body: { dueDate: endOf('2026-10-05') } });
    expect(sent).toContainEqual({ method: 'PATCH', path: '/api/v1/todos/c/planning', body: { workPackageId: 'w-repo' } });
  });

  it('a drop onto the label of a row takes the date away and keeps or sets the package', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('todo:a', 'undated:p1:w-umbau');
    await waitFor(() => expect(sent).toEqual([{ method: 'PUT', path: '/api/v1/todos/a', body: { dueDate: '' } }]));
  });

  it('a drop onto a package of the tray assigns it and keeps the day', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('todo:c', 'pill:p1:w-docs');
    await waitFor(() => expect(sent).toEqual([
      { method: 'PATCH', path: '/api/v1/todos/c/planning', body: { workPackageId: 'w-docs' } },
    ]));
  });

  it('refuses rows of another project, outside and the same spot – nothing is sent', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('todo:a', 'row:p2:w-site', xFor('2026-10-06'));
    dropOn('todo:a', null);
    dropOn('todo:a', 'row:p1:w-umbau', xFor('2026-09-29'));
    dropOn('todo:a', 'strip:p1', xFor('2026-10-06'));
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
    expect(sent).toEqual([]);
  });

  // ff6f08fd: keyboard dragging – Space lifts, the arrows move, Space or Enter drops
  it('a keyboard drop sets the day under the dot of the lifted chip', async () => {
    const sent = recordWrites();
    renderTimeline();

    keyboardDropOn('c', `row:p1:${NO_PACKAGE}`, xFor('2026-10-08'));
    await waitFor(() => expect(sent).toEqual([{ method: 'PUT', path: '/api/v1/todos/c', body: { dueDate: endOf('2026-10-08') } }]));
  });

  it('lifts a chip with Space and keeps Enter for its menu', async () => {
    renderTimeline();
    const chip = screen.getByRole('button', { name: /^Examples page/ });
    expect(chip).toHaveAttribute('aria-roledescription', 'draggable');

    fireEvent.keyDown(chip, { code: 'Enter', key: 'Enter' });
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(document.querySelector('.ring-accent')).toBeNull();

    act(() => { chip.focus(); });
    fireEvent.keyDown(chip, { code: 'Space', key: ' ' });
    await waitFor(() => expect(document.querySelector('.ring-accent')).not.toBeNull());
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
    fireEvent.keyDown(document, { code: 'Escape', key: 'Escape' });
    await waitFor(() => expect(document.querySelector('.ring-accent')).toBeNull());
  });

  it('plans a TODO from its menu – date and package of its own project', async () => {
    const sent = recordWrites();
    const onOpenTodo = renderTimeline();

    fireEvent.click(screen.getByRole('button', { name: /^Examples page/ }));
    const menu = screen.getByRole('dialog', { name: 'Plan: Examples page' });
    // the menu floats above the page, so the scrolling timeline cannot cut it off
    expect(menu.parentElement).toBe(document.body);
    expect(within(menu).getByLabelText('Set date')).toHaveValue('2026-10-02');
    expect(within(within(menu).getByLabelText('Work package')).getAllByRole('option').map((option) => plain(option.textContent)))
      .toEqual(['No work package', 'Umbau · 9/21 – 9/30', 'Repo sauber · 10/1 – 10/7', 'Handbook · no time span']);
    fireEvent.change(within(menu).getByLabelText('Work package'), { target: { value: 'w-repo' } });
    await waitFor(() => expect(sent).toEqual([
      { method: 'PATCH', path: '/api/v1/todos/c/planning', body: { workPackageId: 'w-repo' } },
    ]));

    fireEvent.click(screen.getByRole('button', { name: /^Examples page/ }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Plan: Examples page' })).getByRole('button', { name: 'Edit' }));
    expect(onOpenTodo).toHaveBeenCalledWith('c');
  });
});

describe('GoalTimeline — bars', () => {
  it('moves a bar by whole days, keeping its length', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('wp:w-repo', 'row:p1:w-repo', xFor('2026-10-06'), 3 * PER_DAY);
    await waitFor(() => expect(sent).toEqual([{
      method: 'PUT', path: '/api/v1/goals/g1/work-packages/w-repo',
      body: { startDate: startOf('2026-10-04'), endDate: endOf('2026-10-10') },
    }]));
  });

  it('stretches a bar at its end and at its start', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('wp-end:w-repo', null, 0, 2 * PER_DAY);
    dropOn('wp-start:w-umbau', 'row:p1:w-umbau', 0, -1 * PER_DAY);
    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent).toContainEqual({
      method: 'PUT', path: '/api/v1/goals/g1/work-packages/w-repo', body: { startDate: startOf('2026-10-01'), endDate: endOf('2026-10-09') },
    });
    expect(sent).toContainEqual({
      method: 'PUT', path: '/api/v1/goals/g1/work-packages/w-umbau', body: { startDate: startOf('2026-09-20'), endDate: endOf('2026-09-30') },
    });
  });

  it('gives a package from the tray one week from the day it lands on, and takes the span away back in the tray', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('wp:w-docs', 'row:p1:w-umbau', xFor('2026-10-05'));
    await waitFor(() => expect(sent).toEqual([{
      method: 'PUT', path: '/api/v1/goals/g1/work-packages/w-docs',
      body: { startDate: startOf('2026-10-05'), endDate: endOf('2026-10-11') },
    }]));

    dropOn('wp:w-repo', 'tray:p1');
    await waitFor(() => expect(sent).toContainEqual({
      method: 'PUT', path: '/api/v1/goals/g1/work-packages/w-repo', body: { startDate: '', endDate: '' },
    }));
  });

  it('keeps a bar in its band and ignores a drop on the same days', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('wp:w-repo', 'row:p2:w-site', xFor('2026-10-06'), 3 * PER_DAY);
    dropOn('wp:w-repo', 'row:p1:w-repo', xFor('2026-10-02'), 0.2 * PER_DAY);
    dropOn('wp:w-repo', null, 0, 3 * PER_DAY);
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
    expect(sent).toEqual([]);
  });

  it('by keyboard: the arrows move a bar, Shift with an arrow changes its end, saved after a pause', async () => {
    const sent = recordWrites();
    renderTimeline();

    const bar = within(screen.getByTestId('timeline-bar-w-umbau')).getByRole('button');
    act(() => bar.focus());
    fireEvent.keyDown(bar, { key: 'ArrowRight' });
    fireEvent.keyDown(bar, { key: 'ArrowRight' });
    fireEvent.keyDown(bar, { key: 'ArrowRight', shiftKey: true });
    // at once on screen and read out, not yet saved
    expect(screen.getByTestId('timeline-bar-w-umbau')).toHaveAttribute('data-start', String(dayOfIsoDate('2026-09-23')));
    expect(screen.getByTestId('timeline-bar-w-umbau')).toHaveAttribute('data-end', String(dayOfIsoDate('2026-10-03')));
    expect(plain(screen.getByTestId('timeline-announcer').textContent)).toBe('Umbau: 9/23 – 10/3');
    expect(sent).toEqual([]);

    await act(async () => { await new Promise((resolve) => setTimeout(resolve, NUDGE_DELAY + 50)); });
    await waitFor(() => expect(sent).toEqual([{
      method: 'PUT', path: '/api/v1/goals/g1/work-packages/w-umbau',
      body: { startDate: startOf('2026-09-23'), endDate: endOf('2026-10-03') },
    }]));
  });

  // c60bf914 review: the start of a bar could only be changed with the mouse
  it('by keyboard: Alt with an arrow changes the start of a bar, never past its end, and the help says so', async () => {
    const sent = recordWrites();
    renderTimeline();

    const bar = within(screen.getByTestId('timeline-bar-w-umbau')).getByRole('button');
    expect(document.getElementById(bar.getAttribute('aria-describedby')!)).toHaveTextContent('Alt with an arrow key changes the start');
    act(() => bar.focus());
    fireEvent.keyDown(bar, { key: 'ArrowRight', altKey: true });
    fireEvent.keyDown(bar, { key: 'ArrowRight', altKey: true });
    expect(screen.getByTestId('timeline-bar-w-umbau')).toHaveAttribute('data-start', String(dayOfIsoDate('2026-09-23')));
    expect(screen.getByTestId('timeline-bar-w-umbau')).toHaveAttribute('data-end', String(dayOfIsoDate('2026-09-30')));
    expect(plain(screen.getByTestId('timeline-announcer').textContent)).toBe('Umbau: 9/23 – 9/30');
    // the start never passes the end: eight more steps stop on the last day
    for (let step = 0; step < 8; step++) fireEvent.keyDown(bar, { key: 'ArrowRight', altKey: true });
    expect(screen.getByTestId('timeline-bar-w-umbau')).toHaveAttribute('data-start', String(dayOfIsoDate('2026-09-30')));
    for (let step = 0; step < 7; step++) fireEvent.keyDown(bar, { key: 'ArrowLeft', altKey: true });
    fireEvent.blur(bar);
    await waitFor(() => expect(sent).toEqual([{
      method: 'PUT', path: '/api/v1/goals/g1/work-packages/w-umbau',
      body: { startDate: startOf('2026-09-23'), endDate: endOf('2026-09-30') },
    }]));
  });

  it('by keyboard: leaving the bar saves at once, Escape throws the draft away, Enter opens the card', async () => {
    const sent = recordWrites();
    renderTimeline();

    const bar = within(screen.getByTestId('timeline-bar-w-repo')).getByRole('button');
    act(() => bar.focus());
    fireEvent.keyDown(bar, { key: 'ArrowLeft' });
    fireEvent.keyDown(bar, { key: 'Escape' });
    expect(screen.getByTestId('timeline-bar-w-repo')).toHaveAttribute('data-start', String(dayOfIsoDate('2026-10-01')));
    fireEvent.keyDown(bar, { key: 'ArrowLeft' });
    fireEvent.blur(bar);
    await waitFor(() => expect(sent).toEqual([{
      method: 'PUT', path: '/api/v1/goals/g1/work-packages/w-repo',
      body: { startDate: startOf('2026-09-30'), endDate: endOf('2026-10-06') },
    }]));

    fireEvent.keyDown(bar, { key: 'Enter' });
    const card = screen.getByRole('dialog', { name: 'Work package Repo sauber' });
    expect(within(card).getByLabelText('Start')).toHaveValue('2026-10-01');
    expect(within(card).getByLabelText('End')).toHaveValue('2026-10-07');
  });

  it('edits and deletes a package from its card, and gives the focus back to the bar', async () => {
    const sent = recordWrites();
    renderTimeline();

    const bar = within(screen.getByTestId('timeline-bar-w-repo')).getByRole('button');
    act(() => bar.focus());
    fireEvent.click(bar);
    let card = screen.getByRole('dialog', { name: 'Work package Repo sauber' });
    expect(card.parentElement).toBe(document.body);
    expect(within(card).getByText('Old banner')).toBeInTheDocument();
    fireEvent.change(within(card).getByLabelText('End'), { target: { value: '2026-10-09' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent).toEqual([{
      method: 'PUT', path: '/api/v1/goals/g1/work-packages/w-repo',
      body: { startDate: startOf('2026-10-01'), endDate: endOf('2026-10-09') },
    }]));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Work package Repo sauber' })).not.toBeInTheDocument());
    expect(bar).toHaveFocus();

    fireEvent.click(bar);
    card = screen.getByRole('dialog', { name: 'Work package Repo sauber' });
    fireEvent.click(within(card).getByRole('button', { name: 'Delete' }));
    const question = screen.getByRole('dialog', { name: 'Delete work package' });
    expect(within(question).getByText('Delete the work package “Repo sauber”? Its TODOs stay in the goal.')).toBeInTheDocument();
    fireEvent.click(within(question).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(sent).toContainEqual({ method: 'DELETE', path: '/api/v1/goals/g1/work-packages/w-repo', body: null }));
  });
});

describe('GoalTimeline — adding with a click', () => {
  it('a click into a band opens a field with project and day set; the title and Enter add a package of one week', async () => {
    const sent = recordWrites();
    renderTimeline();

    fireEvent.click(screen.getByTestId('timeline-strip-picker-p1'), { clientX: xFor('2026-10-12') });
    const field = screen.getByRole('textbox', { name: 'New work package in AutoPilot from 10/12/2026, one week' });
    expect(field).toHaveFocus();
    expect(field.closest('form')!.parentElement).toBe(document.body);
    fireEvent.change(field, { target: { value: '  Release candidate ' } });
    fireEvent.submit(field.closest('form')!);
    await waitFor(() => expect(sent).toEqual([{
      method: 'POST', path: '/api/v1/goals/g1/work-packages',
      body: { title: 'Release candidate', projectId: 'p1', startDate: startOf('2026-10-12'), endDate: endOf('2026-10-18') },
    }]));
    await waitFor(() => expect(screen.queryByTestId('inline-title')).not.toBeInTheDocument());
  });

  it('by keyboard: pick the day in the band with the arrows, Enter opens the field; Escape gives the focus back', async () => {
    const sent = recordWrites();
    renderTimeline();

    const picker = screen.getByRole('slider', { name: 'New work package in Site' });
    act(() => picker.focus());
    expect(picker).toHaveAttribute('aria-valuetext', '9/24/2026');
    fireEvent.keyDown(picker, { key: 'ArrowRight' });
    fireEvent.keyDown(picker, { key: 'PageDown' });
    expect(picker).toHaveAttribute('aria-valuetext', '10/2/2026');
    fireEvent.keyDown(picker, { key: 'Enter' });
    let field = screen.getByRole('textbox', { name: 'New work package in Site from 10/2/2026, one week' });
    fireEvent.keyDown(field, { key: 'Escape' });
    expect(picker).toHaveFocus();

    fireEvent.keyDown(picker, { key: 'Enter' });
    field = screen.getByRole('textbox', { name: /^New work package in Site from/ });
    fireEvent.change(field, { target: { value: 'Launch page' } });
    fireEvent.submit(field.closest('form')!);
    await waitFor(() => expect(sent).toEqual([{
      method: 'POST', path: '/api/v1/goals/g1/work-packages',
      body: { title: 'Launch page', projectId: 'p2', startDate: startOf('2026-10-02'), endDate: endOf('2026-10-08') },
    }]));
  });

  it('"+ work package" in the band head adds one without a time span in the tray', async () => {
    const sent = recordWrites();
    renderTimeline();

    const add = screen.getByRole('button', { name: 'New work package in Site' });
    fireEvent.click(add);
    const field = within(screen.getByTestId('timeline-tray-p2')).getByRole('textbox', { name: 'New work package in Site without a time span' });
    fireEvent.change(field, { target: { value: 'Press kit' } });
    fireEvent.submit(field.closest('form')!);
    await waitFor(() => expect(sent).toEqual([
      { method: 'POST', path: '/api/v1/goals/g1/work-packages', body: { title: 'Press kit', projectId: 'p2' } },
    ]));
    await waitFor(() => expect(screen.queryByTestId('inline-title')).not.toBeInTheDocument());
    expect(add).toHaveFocus();
  });

  it('a click into the milestone row – or on the axis – adds a milestone on that day', async () => {
    const sent = recordWrites();
    renderTimeline();

    fireEvent.click(screen.getByTestId('timeline-milestone-picker'), { clientX: xFor('2026-10-12') });
    const field = screen.getByRole('textbox', { name: 'New milestone on 10/12/2026' });
    fireEvent.change(field, { target: { value: 'Repo public' } });
    fireEvent.submit(field.closest('form')!);
    await waitFor(() => expect(sent).toEqual([
      { method: 'POST', path: '/api/v1/goals/g1/milestones', body: { title: 'Repo public', dueDate: endOf('2026-10-12') } },
    ]));
    await waitFor(() => expect(screen.queryByTestId('inline-title')).not.toBeInTheDocument());

    fireEvent.click(screen.getByTestId('timeline-axis'), { clientX: xFor('2026-10-05') });
    expect(screen.getByRole('textbox', { name: 'New milestone on 10/5/2026' })).toBeInTheDocument();
  });

  // c60bf914: in StrictMode (the dev server) the field lost its focus to the day picker while React
  // set its effects up twice – and an empty field that loses the focus closes
  it('keeps the new field open and focused in StrictMode, and an empty field still closes when left', () => {
    renderTimeline({ strict: true });

    fireEvent.click(screen.getByTestId('timeline-milestone-picker'), { clientX: xFor('2026-10-12') });
    const field = screen.getByRole('textbox', { name: 'New milestone on 10/12/2026' });
    expect(field).toHaveFocus();

    fireEvent.blur(field);
    expect(screen.queryByTestId('inline-title')).not.toBeInTheDocument();
  });

  it('"+" in the milestone row opens the form with title, date and packages', async () => {
    const sent = recordWrites();
    renderTimeline();

    fireEvent.click(within(screen.getByTestId('timeline-milestones')).getByRole('button', { name: 'New milestone' }));
    const card = screen.getByRole('dialog', { name: 'New milestone' });
    expect(within(card).getByLabelText('Date')).toHaveValue('2026-09-24');
    fireEvent.change(within(card).getByLabelText('Title'), { target: { value: 'Docs frozen' } });
    fireEvent.click(within(card).getByRole('checkbox', { name: /Handbook/ }));
    fireEvent.click(within(card).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent).toEqual([{
      method: 'POST', path: '/api/v1/goals/g1/milestones', body: { title: 'Docs frozen', dueDate: endOf('2026-09-24'), workPackageIds: ['w-docs'] },
    }]));
  });
});

describe('GoalTimeline — milestones', () => {
  it('moves a diamond to another day – anywhere on the axis – but not into the tray', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('ms:m-freeze', 'milestones', xFor('2026-10-09'));
    dropOn('ms:m-launch', 'row:p2:w-site', xFor('2026-10-13'));
    dropOn('ms:m-launch', 'tray:p1');
    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent).toContainEqual({ method: 'PUT', path: '/api/v1/goals/g1/milestones/m-freeze', body: { dueDate: endOf('2026-10-09') } });
    expect(sent).toContainEqual({ method: 'PUT', path: '/api/v1/goals/g1/milestones/m-launch', body: { dueDate: endOf('2026-10-13') } });
  });

  it('by keyboard: the arrows move a diamond by a day, saved when leaving it', async () => {
    const sent = recordWrites();
    renderTimeline();

    const diamond = screen.getByRole('button', { name: /^Milestone Code freeze/ });
    act(() => diamond.focus());
    fireEvent.keyDown(diamond, { key: 'ArrowLeft' });
    expect(screen.getByTestId('timeline-milestone-m-freeze')).toHaveAttribute('data-day', String(dayOfIsoDate('2026-10-07')));
    expect(screen.getByTestId('timeline-announcer')).toHaveTextContent('Code freeze: Wed, 10/7');
    fireEvent.blur(diamond);
    await waitFor(() => expect(sent).toEqual([
      { method: 'PUT', path: '/api/v1/goals/g1/milestones/m-freeze', body: { dueDate: endOf('2026-10-07') } },
    ]));
  });

  it('opens the card of a milestone: links by project, packages progress, save and delete; focus back to the diamond', async () => {
    const sent = recordWrites();
    renderTimeline();

    const diamond = screen.getByRole('button', { name: /^Milestone Code freeze/ });
    act(() => diamond.focus());
    fireEvent.click(diamond);
    let card = screen.getByRole('dialog', { name: 'Milestone Code freeze' });
    expect(within(card).getByRole('group', { name: 'AutoPilot' })).toBeInTheDocument();
    expect(within(within(card).getByRole('group', { name: 'Site' })).getByRole('checkbox', { name: /Werbematerial/ })).not.toBeChecked();
    expect(within(card).getByRole('checkbox', { name: /Umbau/ })).toBeChecked();
    expect(within(card).getByTestId('milestone-packages-progress')).toHaveTextContent('0 of 2 linked packages complete');
    expect(within(card).queryByRole('switch', { name: 'Reached' })).not.toBeInTheDocument();
    fireEvent.keyDown(card, { key: 'Escape' });
    expect(diamond).toHaveFocus();

    fireEvent.click(diamond);
    card = screen.getByRole('dialog', { name: 'Milestone Code freeze' });
    fireEvent.click(within(card).getByRole('checkbox', { name: /Werbematerial/ }));
    fireEvent.click(within(card).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent).toEqual([{
      method: 'PUT', path: '/api/v1/goals/g1/milestones/m-freeze', body: { workPackageIds: ['w-umbau', 'w-repo', 'w-site'] },
    }]));

    fireEvent.click(screen.getByRole('button', { name: /^Milestone Launch/ }));
    card = screen.getByRole('dialog', { name: 'Milestone Launch' });
    fireEvent.click(within(card).getByRole('switch', { name: 'Reached' }));
    await waitFor(() => expect(sent).toContainEqual({ method: 'PUT', path: '/api/v1/goals/g1/milestones/m-launch', body: { reached: true } }));

    fireEvent.click(within(screen.getByRole('dialog', { name: 'Milestone Launch' })).getByRole('button', { name: 'Delete' }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Delete milestone' })).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(sent).toContainEqual({ method: 'DELETE', path: '/api/v1/goals/g1/milestones/m-launch', body: null }));
  });

  it('hides the milestone controls behind an older backend and says why', () => {
    renderTimeline({ legacy: true });
    expect(screen.queryByTestId('timeline-milestones')).not.toBeInTheDocument();
    expect(screen.getByTestId('timeline-milestones-legacy')).toHaveTextContent('Milestones become available after the backend restart.');
    fireEvent.click(screen.getByTestId('timeline-axis'), { clientX: xFor('2026-10-05') });
    expect(screen.queryByTestId('inline-title')).not.toBeInTheDocument();
  });
});

// Flo: „Beim Reinziehen soll es … unten drunter Arbeitspaket und Datum einblenden“
describe('GoalTimeline — live line while dragging', () => {
  const live = () => plain(screen.getByTestId('timeline-live').textContent);

  it('shows a TODO its package and day, "no date" at a label, and refuses another project', () => {
    renderTimeline();

    startDrag('todo:c');
    moveDrag('todo:c', 'row:p1:w-repo', xFor('2026-10-05'));
    expect(live()).toBe('→ Repo sauber · Mon, 10/5');
    moveDrag('todo:c', `undated:p1:${NO_PACKAGE}`);
    expect(live()).toBe('→ No work package · no date');
    moveDrag('todo:c', 'row:p2:w-site', xFor('2026-10-05'));
    expect(live()).toBe('Not here – only in AutoPilot');
    expect(screen.getByTestId('timeline-live')).toHaveAttribute('data-refused', 'true');
    // rows of the other project fade, rows of its own project light up
    expect(screen.getByTestId('timeline-row-p2-w-site').lastElementChild).toHaveAttribute('data-accepts', 'false');
    expect(screen.getByTestId('timeline-row-p1-w-repo').lastElementChild).toHaveAttribute('data-accepts', 'true');
    moveDrag('todo:c', null);
    expect(live()).toBe('Release to cancel');
  });

  it('shows a moved bar its new span and a dashed ghost where it lands, "no time span" over the tray', () => {
    renderTimeline();

    startDrag('wp:w-repo');
    moveDrag('wp:w-repo', 'row:p1:w-repo', xFor('2026-10-06'), 3 * PER_DAY);
    expect(live()).toBe('10/4 – 10/10');
    expect(within(screen.getByTestId('timeline-row-p1-w-repo')).getByTestId('timeline-ghost')).toBeInTheDocument();
    moveDrag('wp:w-repo', 'tray:p1');
    expect(live()).toBe('→ No time span');
    act(() => dnd.props!.onDragCancel!({} as never));

    // Site has no package without a span – its tray appears only while one of its bars is dragged
    expect(screen.queryByTestId('timeline-tray-p2')).not.toBeInTheDocument();
    startDrag('wp:w-site');
    expect(screen.getByTestId('timeline-tray-p2')).toBeInTheDocument();
  });

  it('stretches the bar itself while its end is dragged and shows the span under it', () => {
    renderTimeline();

    startDrag('wp-end:w-repo');
    moveDrag('wp-end:w-repo', null, 0, 2 * PER_DAY);
    const bar = screen.getByTestId('timeline-bar-w-repo');
    expect(bar).toHaveAttribute('data-end', String(dayOfIsoDate('2026-10-09')));
    expect(plain(within(bar).getByTestId('timeline-live').textContent)).toBe('10/1 – 10/9');
    // no picture under the pointer while stretching – the bar grows in place
    expect(within(screen.getByTestId('drag-overlay')).queryByTestId('timeline-overlay')).not.toBeInTheDocument();
  });

  it('draws the picture under the pointer as large as the dragged item – a compact chip stays compact', () => {
    renderTimeline();

    act(() => dnd.props!.onDragStart!({
      active: {
        id: 'todo:b', data: { current: { anchor: CHIP_ANCHOR, flipped: false, kind: 'todo', compact: true } },
        rect: { current: { initial: { width: 120, height: 24, left: 0, top: 0, right: 120, bottom: 24 }, translated: null } },
      },
    } as unknown as DragStartEvent));
    const overlay = screen.getByTestId('timeline-overlay');
    expect(overlay).toHaveStyle({ width: '120px' });
    expect(overlay.firstElementChild).toHaveClass('h-6');
  });

  it('shows a milestone its day and a TODO the row "no work package" that appears while it is dragged', () => {
    renderTimeline();

    startDrag('ms:m-freeze');
    moveDrag('ms:m-freeze', 'milestones', xFor('2026-10-09'));
    expect(live()).toBe('Fri, 10/9');
    // c60bf914 review: every row where the diamond may land lights up – of every project – the tray stays calm
    expect(screen.getByTestId('timeline-row-p1-w-repo').lastElementChild).toHaveAttribute('data-accepts', 'true');
    expect(screen.getByTestId('timeline-row-p2-w-site').lastElementChild).toHaveAttribute('data-accepts', 'true');
    expect(screen.getByTestId('timeline-strip-p2')).toHaveAttribute('data-accepts', 'true');
    expect(screen.getByTestId('timeline-milestones').lastElementChild).toHaveAttribute('data-accepts', 'true');
    expect(screen.getByTestId('timeline-tray-p1')).not.toHaveAttribute('data-accepts');
    act(() => dnd.props!.onDragCancel!({} as never));

    // Site has no TODO without a package – the row appears only while one of its TODOs is dragged
    expect(screen.queryByTestId(`timeline-row-p2-${NO_PACKAGE}`)).not.toBeInTheDocument();
    startDrag('todo:s');
    expect(screen.getByTestId(`timeline-row-p2-${NO_PACKAGE}`)).toBeInTheDocument();
  });
});

// ff6f08fd: at 768 px the label "Today" lay on the diamond of the next day
describe('TimelineAxisView — the label "Today"', () => {
  function renderAxis(width: number, targetIso: string) {
    const target = dayOfIsoDate(targetIso)!;
    const narrow = axisFor({ todos, workPackages, milestones, targetDate: goal.targetDate, today: dayOf(NOW) }, width);
    renderWithProviders(
      <TimelineAxisView axis={narrow} width={width} targetDay={target} measureRef={createRef()} onCreateAt={vi.fn()} />,
    );
    return screen.getByTestId('timeline-today');
  }

  it('stays centred over today where nothing is near', () => {
    const label = renderAxis(FALLBACK_WIDTH, '2026-10-15');
    expect(label).toHaveTextContent('Today');
    expect(label).toHaveAttribute('data-placement', 'center');
  });

  it('moves aside when the target flag sits on the next day of a narrow axis', () => {
    const label = renderAxis(480, '2026-09-25');
    expect(label).toHaveAttribute('data-placement', 'left');
    expect(label).toHaveClass('-translate-x-full');
  });
});

// c60bf914: a month beginning just before the right end put its label past the edge of the card
describe('TimelineAxisView — labels at the right end', () => {
  /** A week axis from Monday 2026-09-21 to `endIso`, its month bands and Mondays spelt out. */
  function renderUntil(endIso: string, width: number) {
    const startDay = dayOfIsoDate('2026-09-21')!;
    const endDay = dayOfIsoDate(endIso)!;
    const ticks: number[] = [];
    for (let day = startDay; day <= endDay; day += 7) ticks.push(day);
    const bands = [startDay, ...['2026-10-01', '2026-11-01'].map((iso) => dayOfIsoDate(iso)!)].filter((day) => day <= endDay);
    const axis: TimelineAxis = { startDay, endDay, days: endDay - startDay + 1, today: startDay + 2, scale: 'week', ticks, bands };
    renderWithProviders(<TimelineAxisView axis={axis} width={width} targetDay={null} measureRef={createRef()} onCreateAt={vi.fn()} />);
  }

  it('leaves out a month and a week label that would run past the end', () => {
    renderUntil('2026-11-02', 700);
    expect(screen.getByText('Oct 2026')).toBeInTheDocument();
    expect(screen.getByText('10/26')).toBeInTheDocument();
    expect(screen.queryByText('Nov 2026')).not.toBeInTheDocument();
    expect(screen.queryByText('11/2')).not.toBeInTheDocument();
  });

  it('keeps them where there is room', () => {
    renderUntil('2026-11-15', 700);
    expect(screen.getByText('Nov 2026')).toBeInTheDocument();
    expect(screen.getByText('11/2')).toBeInTheDocument();
    expect(screen.getByText('11/9')).toBeInTheDocument();
  });
});

// 68a5e24e: the chips carry ❓/✔ and their menu decides
describe('GoalTimeline — decisions', () => {
  const asking = todos.map((todo) => (todo.id === 'a'
    ? { ...todo, openQuestion: 'Which licence?', decisionState: 'QUESTION' as const }
    : todo.id === 'c' ? { ...todo, decision: 'Plain HTML', decidedAt: '2026-09-26T08:00:00Z', decidedBy: 'USER' as const, decisionState: 'DECIDED' as const }
      : todo));

  it('marks a chip with ❓ or ✔ and decides from its menu', async () => {
    const sent: unknown[] = [];
    server.use(http.patch('/api/v1/todos/:id/planning', async ({ request }) => {
      sent.push(await request.json());
      return HttpResponse.json({});
    }));
    renderTimeline({ todos: asking, decisions: true });

    const chip = screen.getByRole('button', { name: /^Privacy findings – / });
    expect(within(chip).getByTestId('decision-mark')).toHaveAttribute('data-state', 'QUESTION');
    expect(within(screen.getByRole('button', { name: /^Examples page – / })).getByTestId('decision-mark'))
      .toHaveAttribute('aria-label', 'Decided: Plain HTML (9/26/2026, by a person)');

    fireEvent.click(chip);
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Plan: Privacy findings' })).getByRole('button', { name: 'Decide' }));
    const card = screen.getByRole('dialog', { name: 'Decision on Privacy findings' });
    fireEvent.change(within(card).getByLabelText('Decision'), { target: { value: 'MIT' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Decide' }));
    await waitFor(() => expect(sent).toEqual([{ decision: 'MIT' }]));
  });

  it('offers no deciding in the menu of a chip without question and decision', () => {
    renderTimeline({ todos: asking, decisions: true });
    fireEvent.click(screen.getByRole('button', { name: /^Old banner – / }));
    const menu = screen.getByRole('dialog', { name: 'Plan: Old banner' });
    expect(within(menu).queryByRole('button', { name: 'Decide' })).not.toBeInTheDocument();
    expect(within(menu).queryByRole('button', { name: 'Change' })).not.toBeInTheDocument();
  });
});

// 52bc1e8c: the workflows of a package stand at its row and in its card
describe('GoalTimeline — workflows', () => {
  it('shows a package\'s workflows in its row label and its card, where "Assign workflow" opens the dialog', () => {
    const openAssign = vi.fn();
    const attachment = {
      id: 'a2', goalId: 'g1', workPackageId: 'w-repo', workflowId: 'wf-fix', workflowName: 'Fix bot', workflowProjectId: 'p1',
      role: 'WORKER' as const, guard: false,
    };
    renderWithProviders(
      <GoalWorkflowsContext.Provider value={{ goalId: 'g1', supported: true, attachments: [attachment], results: new Map(), openAssign }}>
        <GoalTimeline goal={goal} todos={todos} workPackages={workPackages} milestones={milestones} projects={projects}
          onOpenTodo={vi.fn()} now={NOW} />
      </GoalWorkflowsContext.Provider>,
    );

    const row = screen.getByTestId('timeline-row-p1-w-repo');
    expect(within(row).getByTestId('goal-workflow-a2')).toHaveAttribute('data-tone', 'neutral');
    expect(within(row).getByRole('button', { name: 'Start: Fix bot' })).toBeInTheDocument();

    fireEvent.click(within(screen.getByTestId('timeline-bar-w-repo')).getByRole('button'));
    const card = screen.getByRole('dialog', { name: 'Work package Repo sauber' });
    expect(within(card).getByTestId('package-workflow-list-w-repo')).toHaveTextContent('Fix bot');
    fireEvent.click(within(card).getByRole('button', { name: 'Assign workflow' }));
    expect(openAssign).toHaveBeenCalledWith('w-repo');
    expect(screen.queryByRole('dialog', { name: 'Work package Repo sauber' })).not.toBeInTheDocument();
  });
});

// Review 52bc1e8c: two ▶ for the same workflow (row chip and package card) must not start it twice
describe('GoalTimeline — starting a workflow once', () => {
  const attachment = {
    id: 'a2', goalId: 'g1', workPackageId: 'w-repo', workflowId: 'wf-fix', workflowName: 'Fix bot', workflowProjectId: 'p1',
    role: 'WORKER' as const, guard: false,
  };
  const renderWith = (results: Map<string, never>) => renderWithProviders(
    <GoalWorkflowsContext.Provider value={{ goalId: 'g1', supported: true, attachments: [attachment], results, openAssign: vi.fn() }}>
      <GoalTimeline goal={goal} todos={todos} workPackages={workPackages} milestones={milestones} projects={projects}
        onOpenTodo={vi.fn()} now={NOW} />
    </GoalWorkflowsContext.Provider>,
  );

  it('sends one call when the chip and the card are clicked right after each other, and locks both while it starts', async () => {
    const calls: string[] = [];
    let release = () => {};
    server.use(http.post('/api/v1/workflows/:id/execute', async ({ params }) => {
      calls.push(String(params.id));
      await new Promise<void>((resolve) => { release = resolve; });
      return HttpResponse.json({ id: 'run-1', status: 'PENDING' });
    }));
    renderWith(new Map());

    fireEvent.click(within(screen.getByTestId('timeline-bar-w-repo')).getByRole('button'));
    const card = screen.getByRole('dialog', { name: 'Work package Repo sauber' });
    const inCard = within(card).getByRole('button', { name: 'Start: Fix bot' });
    const inRow = within(screen.getByTestId('timeline-row-p1-w-repo')).getByRole('button', { name: 'Start: Fix bot' });
    fireEvent.click(inRow);
    fireEvent.click(inCard);
    fireEvent.click(inRow);
    await waitFor(() => expect(calls).toEqual(['wf-fix']));
    await waitFor(() => expect(inCard).toBeDisabled());
    expect(inRow).toBeDisabled();
    act(() => release());
    await waitFor(() => expect(inRow).toBeEnabled());
    expect(calls).toEqual(['wf-fix']);
  });

  it('locks ▶ while the last run of the workflow is still running', () => {
    const running = {
      workflowId: 'wf-fix', workflowName: 'Fix bot', available: true, resultState: 'MISSING', result: null,
      run: { runId: 'r1', status: 'RUNNING', startedAt: new Date().toISOString(), completedAt: null, durationMs: null, gateStatus: null },
    };
    renderWith(new Map([['wf-fix', running as never]]));
    const button = within(screen.getByTestId('timeline-row-p1-w-repo')).getByRole('button', { name: 'Start: Fix bot' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', 'Running – can be started again once it ends');
  });
});
