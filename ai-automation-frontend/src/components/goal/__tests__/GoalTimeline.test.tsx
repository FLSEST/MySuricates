import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { DndContextProps, DragEndEvent, DragStartEvent } from '@dnd-kit/core';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { createRef } from 'react';
import { GoalTimeline } from '../GoalTimeline';
import { TimelineAxisView } from '../TimelineAxisView';
import {
  CHIP_ANCHOR, CHIP_WIDTH, DIAMOND_ANCHOR, FALLBACK_WIDTH, NO_MILESTONE, axisFor, dayOf, dayOfIsoDate, isDated, xOfDay,
} from '../timelineLayout';
import type { Goal, GoalMilestone, GoalProject, GoalTodoItem } from '../../../types';

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
  id: 'm-beta', goalId: 'g1', title: 'Beta', dueDate: endOf('2026-10-01'), projectId: 'p1', position: 0, openQuestions: 0,
  state: 'OPEN', total: 1, done: 0,
  reachedAt: null, reopened: false, createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
};
/** Ein Teilziel ohne Datum (c60bf914) – es wartet in der Leiste „ohne Datum“ seines Bands. */
const docs: GoalMilestone = { ...beta, id: 'm-docs', title: 'Docs', dueDate: null, position: 1, total: 0 };
const milestones = [beta, docs];

const projects: GoalProject[] = [
  { projectId: 'p1', name: 'AutoPilot', archived: false, position: 0, total: 5, done: 1, openQuestions: 0 },
  { projectId: 'p2', name: 'Site', archived: false, position: 1, total: 1, done: 0, openQuestions: 0 },
];

const todos: GoalTodoItem[] = [
  item({ id: 'a', title: 'Privacy findings', dueDate: endOf('2026-09-30'), milestoneId: 'm-beta' }),
  item({ id: 'b', title: 'Style probe', dueDate: endOf('2026-10-05') }),
  item({ id: 'c', title: 'Examples page' }),
  item({ id: 'd', title: 'Old banner', dueDate: endOf('2026-09-20') }),
  item({ id: 'e', title: 'Release notes', status: 'DONE', dueDate: endOf('2026-09-22') }),
  item({ id: 's', title: 'Site copy', projectId: 'p2', projectName: 'Site', dueDate: endOf('2026-10-03') }),
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
  renderWithProviders(
    <GoalTimeline goal={goal} todos={todos} milestones={milestones} projects={projects} onOpenTodo={onOpenTodo} now={NOW} />,
  );
  return onOpenTodo;
}

/** Wo der Tag im Zeitstrahl liegt – dieselbe Achse, die der Zeitstrahl ohne gemessene Breite zeichnet. */
function xFor(iso: string): number {
  const axis = axisFor({ todos, milestones, targetDate: goal.targetDate, today: dayOf(NOW) }, FALLBACK_WIDTH);
  return xOfDay(axis, dayOfIsoDate(iso)!, FALLBACK_WIDTH);
}

/**
 * Das Ende eines Zugs: `activeId` (`todo:…` oder `ms:…`) losgelassen über `overId`, Zeiger `x` Pixel
 * rechts vom Anfang der Fläche.
 */
function dropOn(activeId: string, overId: string | null, x = 0) {
  const rowLeft = 180;
  const event = {
    active: { id: activeId.includes(':') ? activeId : `todo:${activeId}`, data: { current: undefined }, rect: { current: { initial: null, translated: null } } },
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

/**
 * Das Ende eines Zugs per Tastatur: kein Zeiger, der Tag steht unter dem Statuspunkt des angehobenen
 * Chips – `dotX` Pixel rechts vom Anfang der Zeile (ff6f08fd).
 */
function keyboardDropOn(activeId: string, overId: string, dotX: number, anchor = CHIP_ANCHOR) {
  const rowLeft = 180;
  const left = rowLeft + dotX - anchor;
  const event = {
    active: {
      id: activeId.includes(':') ? activeId : `todo:${activeId}`,
      data: { current: { anchor, flipped: false, kind: activeId.startsWith('ms:') ? 'milestone' : 'todo' } },
      rect: { current: { initial: null, translated: { left, top: 310, width: CHIP_WIDTH, height: 28, right: left + CHIP_WIDTH, bottom: 338 } } },
    },
    over: {
      id: overId, data: { current: undefined }, disabled: false,
      rect: { left: rowLeft, top: 300, width: FALLBACK_WIDTH, height: 48, right: rowLeft + FALLBACK_WIDTH, bottom: 348 },
    },
    delta: { x: 3 * 30, y: 0 },
    activatorEvent: new KeyboardEvent('keydown', { code: 'Space' }),
    collisions: null,
  } as unknown as DragEndEvent;
  act(() => dnd.props!.onDragEnd!(event));
}

describe('GoalTimeline', () => {
  it('shows a band per project with a row per sub-goal and one without, dated TODOs on the axis, undated ones beside it in their row', () => {
    renderTimeline();

    const autopilot = screen.getByRole('region', { name: 'Project band AutoPilot' });
    const site = screen.getByRole('region', { name: 'Project band Site' });
    const betaRow = within(autopilot).getByTestId('timeline-row-p1-m-beta');
    expect(within(betaRow).getByRole('button', { name: /^Privacy findings/ })).toBeInTheDocument();
    expect(within(site).getByRole('button', { name: /^Site copy/ })).toBeInTheDocument();
    // the undated sub-goal waits in the tray of its band
    expect(within(screen.getByTestId('timeline-tray-p1')).getByRole('button', { name: 'Sub-goal Docs – open, no date' }))
      .toBeInTheDocument();
    const noneRow = screen.getByTestId(`timeline-row-p1-${NO_MILESTONE}`);
    expect(within(noneRow).getByRole('button', { name: /^Style probe/ })).toBeInTheDocument();
    // not on the axis part of the row …
    expect(within(noneRow.lastElementChild as HTMLElement).queryByRole('button', { name: /^Examples page/ })).not.toBeInTheDocument();

    // c60bf914: without a due date a TODO stays in the row of its sub-goal (here: none) instead of a global pile
    const undated = screen.getByTestId(`timeline-undated-p1-${NO_MILESTONE}`);
    expect(within(undated).getByText('1 without a date')).toBeInTheDocument();
    expect(within(undated).getByRole('button', { name: /^Examples page – no due date/ })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Unplanned' })).not.toBeInTheDocument();

    // überfällig rot, erledigt durchgestrichen
    expect(screen.getByRole('button', { name: /^Old banner/ })).toHaveAccessibleName(/overdue/i);
    expect(screen.getByRole('button', { name: /^Old banner/ })).toHaveAttribute('data-overdue', 'true');
    expect(screen.getByRole('button', { name: /^Release notes/ })).toHaveAttribute('data-done', 'true');
    expect(screen.getByRole('button', { name: /^Release notes/ })).not.toHaveAttribute('data-overdue', 'true');

    // Raute je datiertes Teilziel in seinem Band, auf der Achse Fahne für das Zieldatum und heute
    expect(within(autopilot).getByRole('button', { name: /^Sub-goal Beta – open, due/ })).toBeInTheDocument();
    expect(within(screen.getByTestId('timeline-axis')).getByText('Today')).toBeInTheDocument();
    expect(screen.getByTestId('timeline-target')).toBeInTheDocument();
  });

  it('a drop on another day sets the due date at 23:59:59 local time', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('b', `row:p1:${NO_MILESTONE}`, xFor('2026-10-07'));

    await waitFor(() => expect(sent).toEqual([
      { method: 'PUT', path: '/api/v1/todos/b', body: { dueDate: endOf('2026-10-07') } },
    ]));
  });

  it('a drop into a sub-goal row sets the sub-goal, keeping the date', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('b', 'row:p1:m-beta', xFor('2026-10-05'));

    await waitFor(() => expect(sent).toEqual([
      { method: 'PATCH', path: '/api/v1/todos/b/planning', body: { milestoneId: 'm-beta' } },
    ]));
  });

  it('an unplanned TODO dropped into a sub-goal row gets date and sub-goal', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('c', 'row:p1:m-beta', xFor('2026-09-29'));

    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent).toContainEqual({ method: 'PUT', path: '/api/v1/todos/c', body: { dueDate: endOf('2026-09-29') } });
    expect(sent).toContainEqual({ method: 'PATCH', path: '/api/v1/todos/c/planning', body: { milestoneId: 'm-beta' } });
  });

  it('back beside the axis clears the date; a drop outside or on the same spot changes nothing', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('a', null);
    dropOn('b', `row:p1:${NO_MILESTONE}`, xFor('2026-10-05'));
    dropOn('a', 'undated:p1:m-beta');

    await waitFor(() => expect(sent).toEqual([
      { method: 'PUT', path: '/api/v1/todos/a', body: { dueDate: '' } },
    ]));
  });

  // ff6f08fd: keyboard dragging – Space lifts, the arrows move, Space or Enter drops
  it('a keyboard drop sets the day under the dot of the lifted chip', async () => {
    const sent = recordWrites();
    renderTimeline();

    keyboardDropOn('b', `row:p1:${NO_MILESTONE}`, xFor('2026-10-08'));

    await waitFor(() => expect(sent).toEqual([
      { method: 'PUT', path: '/api/v1/todos/b', body: { dueDate: endOf('2026-10-08') } },
    ]));
  });

  it('lifts a chip with Space and keeps Enter for its menu', async () => {
    renderTimeline();
    const chip = screen.getByRole('button', { name: /^Style probe/ });
    expect(chip).toHaveAttribute('aria-roledescription', 'draggable');
    const lifted = () => document.querySelector('.ring-accent');

    fireEvent.keyDown(chip, { code: 'Enter', key: 'Enter' });
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(lifted()).toBeNull();

    act(() => { chip.focus(); });
    fireEvent.keyDown(chip, { code: 'Space', key: ' ' });
    await waitFor(() => expect(lifted()).not.toBeNull());
    // the lifted chip is drawn a second time under the pointer, the original fades
    expect(screen.getAllByText('Style probe')).toHaveLength(2);

    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
    fireEvent.keyDown(document, { code: 'Escape', key: 'Escape' });
    await waitFor(() => expect(screen.getAllByText('Style probe')).toHaveLength(1));
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
    expect(within(second).getByLabelText('Sub-goal')).toHaveValue('m-beta');
    // only sub-goals of the TODO's own project are offered
    expect(within(within(second).getByLabelText('Sub-goal')).getAllByRole('option').map((option) => option.textContent))
      .toEqual(['No sub-goal', 'Beta · Oct 1', 'Docs · no date']);
    fireEvent.change(within(second).getByLabelText('Sub-goal'), { target: { value: '' } });
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

  it('adds a sub-goal from the button, with a project and an optional date, and by a click on the axis', async () => {
    const sent = recordWrites();
    renderTimeline();

    fireEvent.click(screen.getByRole('button', { name: 'Sub-goal' }));
    const form = screen.getByRole('dialog', { name: 'New sub-goal' });
    fireEvent.change(within(form).getByLabelText('Title'), { target: { value: 'RC' } });
    expect(within(form).getByLabelText('Project')).toHaveValue('p1');
    fireEvent.change(within(form).getByLabelText('Project'), { target: { value: 'p2' } });
    fireEvent.change(within(form).getByLabelText('Date (optional)'), { target: { value: '2026-10-08' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent).toEqual([
      { method: 'POST', path: '/api/v1/goals/g1/milestones', body: { title: 'RC', projectId: 'p2', dueDate: endOf('2026-10-08') } },
    ]));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New sub-goal' })).not.toBeInTheDocument());

    // without a date the request carries none
    fireEvent.click(screen.getByRole('button', { name: 'Sub-goal' }));
    const undated = screen.getByRole('dialog', { name: 'New sub-goal' });
    fireEvent.change(within(undated).getByLabelText('Title'), { target: { value: 'Someday' } });
    fireEvent.click(within(undated).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent).toContainEqual(
      { method: 'POST', path: '/api/v1/goals/g1/milestones', body: { title: 'Someday', projectId: 'p1' } },
    ));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New sub-goal' })).not.toBeInTheDocument());

    fireEvent.click(screen.getByTestId('timeline-axis'), { clientX: xFor('2026-10-12') });
    expect(within(screen.getByRole('dialog', { name: 'New sub-goal' })).getByLabelText('Date (optional)')).toHaveValue('2026-10-12');
  });

  it('a diamond opens the sub-goal card with its TODOs, to edit or delete it', async () => {
    const sent = recordWrites();
    renderTimeline();

    fireEvent.click(screen.getByRole('button', { name: /^Sub-goal Beta/ }));
    const card = screen.getByRole('dialog', { name: 'Sub-goal Beta' });
    expect(within(card).getByText('Privacy findings')).toBeInTheDocument();
    expect(within(card).getByText('0 of 1 done')).toBeInTheDocument();

    fireEvent.click(within(card).getByRole('button', { name: 'Edit' }));
    const form = screen.getByRole('dialog', { name: 'Edit sub-goal' });
    expect(within(form).getByLabelText('Date (optional)')).toHaveValue('2026-10-01');
    // a sub-goal with TODOs keeps its project
    expect(within(form).getByLabelText('Project')).toBeDisabled();
    fireEvent.change(within(form).getByLabelText('Title'), { target: { value: 'Public beta' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent).toEqual([
      { method: 'PUT', path: '/api/v1/goals/g1/milestones/m-beta', body: { title: 'Public beta' } },
    ]));

    // 23635ef6: the timeline asks in its own dialog; the card closes, a click in the dialog must not matter to it
    fireEvent.click(screen.getByRole('button', { name: /^Sub-goal Beta/ }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Sub-goal Beta' })).getByRole('button', { name: 'Delete' }));
    expect(screen.queryByRole('dialog', { name: 'Sub-goal Beta' })).not.toBeInTheDocument();
    const question = screen.getByRole('dialog', { name: 'Delete sub-goal' });
    expect(within(question).getByText('Delete the sub-goal “Beta”? Its TODOs stay in the goal.')).toBeInTheDocument();
    fireEvent.click(within(question).getByRole('button', { name: 'Cancel' }));
    expect(sent.filter((call) => call.method === 'DELETE')).toEqual([]);

    fireEvent.click(screen.getByRole('button', { name: /^Sub-goal Beta/ }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Sub-goal Beta' })).getByRole('button', { name: 'Delete' }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Delete sub-goal' })).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(sent).toContainEqual({ method: 'DELETE', path: '/api/v1/goals/g1/milestones/m-beta', body: null }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Delete sub-goal' })).not.toBeInTheDocument());
  });
});

// c60bf914: sub-goals are added right in their project band – with a click, by keyboard or without a date
describe('GoalTimeline — adding sub-goals in the bands', () => {
  it('a click into a band opens a field with project and day set; the title and Enter add the sub-goal', async () => {
    const sent = recordWrites();
    renderTimeline();

    fireEvent.click(screen.getByTestId('timeline-strip-picker-p1'), { clientX: xFor('2026-10-12') });
    const field = screen.getByRole('textbox', { name: 'New sub-goal in AutoPilot on 10/12/2026' });
    expect(field).toHaveFocus();
    fireEvent.change(field, { target: { value: '  Release candidate ' } });
    fireEvent.submit(field.closest('form')!);

    await waitFor(() => expect(sent).toEqual([{
      method: 'POST', path: '/api/v1/goals/g1/milestones',
      body: { title: 'Release candidate', projectId: 'p1', dueDate: endOf('2026-10-12') },
    }]));
    await waitFor(() => expect(screen.queryByTestId('inline-subgoal')).not.toBeInTheDocument());
  });

  it('by keyboard: focus the band, pick the day with the arrow keys, Enter opens the field', async () => {
    const sent = recordWrites();
    renderTimeline();

    const picker = screen.getByRole('slider', { name: 'New sub-goal in Site' });
    act(() => picker.focus());
    expect(picker).toHaveAttribute('aria-valuetext', '9/24/2026');
    fireEvent.keyDown(picker, { key: 'ArrowRight' });
    fireEvent.keyDown(picker, { key: 'ArrowRight' });
    fireEvent.keyDown(picker, { key: 'ArrowLeft' });
    fireEvent.keyDown(picker, { key: 'PageDown' });
    expect(picker).toHaveAttribute('aria-valuetext', '10/2/2026');
    fireEvent.keyDown(picker, { key: 'Enter' });

    const field = screen.getByRole('textbox', { name: 'New sub-goal in Site on 10/2/2026' });
    fireEvent.change(field, { target: { value: 'Launch page' } });
    fireEvent.submit(field.closest('form')!);
    await waitFor(() => expect(sent).toEqual([{
      method: 'POST', path: '/api/v1/goals/g1/milestones',
      body: { title: 'Launch page', projectId: 'p2', dueDate: endOf('2026-10-02') },
    }]));
  });

  it('"+ sub-goal" of a band adds one without a date in its tray; Escape cancels', async () => {
    const sent = recordWrites();
    renderTimeline();

    fireEvent.click(screen.getByRole('button', { name: 'New sub-goal in Site' }));
    const tray = screen.getByTestId('timeline-tray-p2');
    const field = within(tray).getByRole('textbox', { name: 'New sub-goal in Site without a date' });
    fireEvent.keyDown(field, { key: 'Escape' });
    expect(screen.queryByTestId('inline-subgoal')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'New sub-goal in Site' }));
    const again = within(screen.getByTestId('timeline-tray-p2')).getByRole('textbox');
    fireEvent.change(again, { target: { value: 'Press kit' } });
    fireEvent.submit(again.closest('form')!);
    await waitFor(() => expect(sent).toEqual([
      { method: 'POST', path: '/api/v1/goals/g1/milestones', body: { title: 'Press kit', projectId: 'p2' } },
    ]));
  });
});

describe('GoalTimeline — dragging sub-goals and TODOs between bands', () => {
  it('moves a diamond to another day by mouse and by keyboard', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('ms:m-beta', 'strip:p1', xFor('2026-10-06'));
    await waitFor(() => expect(sent).toEqual([
      { method: 'PUT', path: '/api/v1/goals/g1/milestones/m-beta', body: { dueDate: endOf('2026-10-06') } },
    ]));

    keyboardDropOn('ms:m-beta', 'strip:p1', xFor('2026-10-09'), DIAMOND_ANCHOR);
    await waitFor(() => expect(sent).toContainEqual(
      { method: 'PUT', path: '/api/v1/goals/g1/milestones/m-beta', body: { dueDate: endOf('2026-10-09') } },
    ));
  });

  it('dates an undated sub-goal dragged from the tray onto its band, and takes the date away back in the tray', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('ms:m-docs', `row:p1:${NO_MILESTONE}`, xFor('2026-10-04'));
    await waitFor(() => expect(sent).toEqual([
      { method: 'PUT', path: '/api/v1/goals/g1/milestones/m-docs', body: { dueDate: endOf('2026-10-04') } },
    ]));

    dropOn('ms:m-beta', 'tray:p1');
    await waitFor(() => expect(sent).toContainEqual(
      { method: 'PUT', path: '/api/v1/goals/g1/milestones/m-beta', body: { dueDate: '' } },
    ));
  });

  it('keeps a diamond in its own band', async () => {
    const sent = recordWrites();
    renderTimeline();

    dropOn('ms:m-beta', 'strip:p2', xFor('2026-10-06'));
    dropOn('ms:m-beta', 'unplanned');
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
    expect(sent).toEqual([]);
  });

  it('lights up only the sub-goals of the TODO\'s own project while dragging, and refuses the others', async () => {
    const sent = recordWrites();
    renderTimeline();

    act(() => dnd.props!.onDragStart!({
      active: { id: 'todo:b', data: { current: { anchor: CHIP_ANCHOR, flipped: false, kind: 'todo' } }, rect: { current: { initial: null, translated: null } } },
    } as unknown as DragStartEvent));
    expect(screen.getByTestId('timeline-row-p1-m-beta').lastElementChild).toHaveAttribute('data-accepts', 'true');
    expect(screen.getByTestId('timeline-pill-m-docs')).toHaveClass('bg-accent/5');
    expect(screen.getByTestId(`timeline-row-p2-${NO_MILESTONE}`).lastElementChild).toHaveAttribute('data-accepts', 'false');
    expect(screen.getByTestId('timeline-strip-p1')).toHaveAttribute('data-accepts', 'false');

    dropOn('b', `row:p2:${NO_MILESTONE}`, xFor('2026-10-05'));
    dropOn('b', 'pill:p1:m-docs');
    await waitFor(() => expect(sent).toEqual([
      { method: 'PATCH', path: '/api/v1/todos/b/planning', body: { milestoneId: 'm-docs' } },
    ]));
    expect(screen.getByTestId('timeline-row-p1-m-beta').lastElementChild).not.toHaveAttribute('data-accepts');
  });
});

// c60bf914 (review): TODOs without a due date keep their sub-goal – they stand beside the axis in its row
describe('GoalTimeline — TODOs without a date in their row', () => {
  const pending = ['p', 'q', 'r', 's2', 't'].map((id) => item({ id, title: `Handbook ${id}`, milestoneId: 'm-docs' }));

  function renderWithUndated() {
    renderWithProviders(
      <GoalTimeline goal={goal} todos={[...todos, ...pending]} milestones={milestones} projects={projects} onOpenTodo={vi.fn()} now={NOW} />,
    );
  }

  it('gives an undated sub-goal with TODOs a row, shows three of them and the rest behind "+n"', () => {
    renderWithUndated();

    const undated = screen.getByTestId('timeline-undated-p1-m-docs');
    expect(within(undated).getByRole('button', { name: /^Docs/ })).toHaveTextContent('no date');
    expect(within(undated).getByText('5 without a date')).toBeInTheDocument();
    expect(within(undated).getAllByRole('button', { name: /^Handbook/ })).toHaveLength(3);
    const more = within(undated).getByRole('button', { name: '+2 more' });
    expect(more).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(more);
    expect(within(undated).getAllByRole('button', { name: /^Handbook/ })).toHaveLength(5);
    expect(within(undated).getByRole('button', { name: 'show fewer' })).toHaveAttribute('aria-expanded', 'true');
  });

  it('dates a TODO dragged from beside the axis onto the axis of its row, keeping its sub-goal', async () => {
    const sent = recordWrites();
    renderWithUndated();

    dropOn('p', 'row:p1:m-docs', xFor('2026-10-06'));
    await waitFor(() => expect(sent).toEqual([
      { method: 'PUT', path: '/api/v1/todos/p', body: { dueDate: endOf('2026-10-06') } },
    ]));
  });
});

// c60bf914 (review): menus and cards float above the page, so the self-scrolling timeline cannot cut them off
describe('GoalTimeline — floating cards and focus', () => {
  it('renders menus and cards into the page body, not into the scrolling timeline', () => {
    renderTimeline();

    fireEvent.click(screen.getByRole('button', { name: /^Style probe/ }));
    const menu = screen.getByRole('dialog', { name: 'Plan: Style probe' });
    expect(menu.parentElement).toBe(document.body);
    expect(menu).toHaveStyle({ position: 'fixed' });
    expect(within(screen.getByTestId('goal-timeline')).queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('gives the focus back to the diamond after the card closes, and after editing', async () => {
    recordWrites();
    renderTimeline();

    const diamond = screen.getByRole('button', { name: /^Sub-goal Beta – open, due/ });
    act(() => diamond.focus());
    fireEvent.click(diamond);
    const card = screen.getByRole('dialog', { name: 'Sub-goal Beta' });
    expect(card).toContainElement(document.activeElement as HTMLElement);
    fireEvent.keyDown(card, { key: 'Escape' });
    expect(diamond).toHaveFocus();

    fireEvent.click(diamond);
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Sub-goal Beta' })).getByRole('button', { name: 'Edit' }));
    const form = screen.getByRole('dialog', { name: 'Edit sub-goal' });
    fireEvent.change(within(form).getByLabelText('Title'), { target: { value: 'Public beta' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Edit sub-goal' })).not.toBeInTheDocument());
    expect(diamond).toHaveFocus();
  });

  it('gives the focus back to the day picker and to "+ sub-goal" after the field closes', async () => {
    recordWrites();
    renderTimeline();

    const picker = screen.getByRole('slider', { name: 'New sub-goal in AutoPilot' });
    act(() => picker.focus());
    fireEvent.keyDown(picker, { key: 'Enter' });
    const field = screen.getByRole('textbox', { name: /^New sub-goal in AutoPilot on/ });
    expect(field.closest('form')!.parentElement).toBe(document.body);
    fireEvent.keyDown(field, { key: 'Escape' });
    expect(picker).toHaveFocus();

    const add = screen.getByRole('button', { name: 'New sub-goal in Site' });
    fireEvent.click(add);
    const undatedField = screen.getByRole('textbox', { name: 'New sub-goal in Site without a date' });
    fireEvent.change(undatedField, { target: { value: 'Press kit' } });
    fireEvent.submit(undatedField.closest('form')!);
    await waitFor(() => expect(screen.queryByTestId('inline-subgoal')).not.toBeInTheDocument());
    expect(add).toHaveFocus();
  });
});

// ff6f08fd: at 768 px the label "Today" lay on the diamond of the next day
describe('TimelineAxisView — the label "Today"', () => {
  const nextDay: GoalMilestone = { ...beta, id: 'm-next', title: 'Kick-off', dueDate: endOf('2026-09-25') };

  function renderAxis(width: number, marks: GoalMilestone[]) {
    const axis = axisFor({ todos, milestones: marks, targetDate: goal.targetDate, today: dayOf(NOW) }, width);
    renderWithProviders(
      <TimelineAxisView axis={axis} width={width} milestones={marks.filter(isDated)} targetDay={dayOf(goal.targetDate!)}
        preview={null} measureRef={createRef()} onCreateAt={vi.fn()} />,
    );
    return screen.getByTestId('timeline-today');
  }

  it('stays centred over today where the axis is wide enough', () => {
    const label = renderAxis(FALLBACK_WIDTH, [beta, nextDay]);
    expect(label).toHaveTextContent('Today');
    expect(label).toHaveAttribute('data-placement', 'center');
  });

  it('moves aside when a diamond sits on the next day of a narrow axis', () => {
    const label = renderAxis(480, [beta, nextDay]);
    expect(label).toHaveAttribute('data-placement', 'left');
    expect(label).toHaveClass('-translate-x-full');
  });
});
