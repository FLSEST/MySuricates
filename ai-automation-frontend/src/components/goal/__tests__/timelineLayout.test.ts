import { describe, it, expect } from 'vitest';
import {
  CHIP_WIDTH, FALLBACK_WIDTH, NO_MILESTONE,
  applyPlan, axisFor, buildAxis, buildRows, dateOfDay, dayAtX, dayOf, dayOfIsoDate, dropTargetOf, dueDateOfDay,
  isOverdue, isoDateOfDay, planChanges, stackChips, xOfDay,
} from '../timelineLayout';
import type { GoalMilestone, GoalTodoItem } from '../../../types';

const day = (iso: string) => dayOfIsoDate(iso)!;
/** Ende des lokalen Tages – so speichert der TODO-Dialog eine Fälligkeit. */
const endOf = (iso: string) => new Date(`${iso}T23:59:59`).toISOString();
const W = FALLBACK_WIDTH;

function todo(id: string, overrides: Partial<GoalTodoItem> = {}): GoalTodoItem {
  return {
    id, title: `Todo ${id}`, status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
    goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null,
    openQuestion: null, updatedAt: '2026-09-23T00:00:00Z', dueDate: null, milestoneId: null, ...overrides,
  };
}

function milestone(id: string, title: string, iso: string): GoalMilestone {
  return {
    id, goalId: 'g1', title, dueDate: endOf(iso), state: 'OPEN', total: 0, done: 0, reachedAt: null, reopened: false,
    createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
  };
}

describe('days', () => {
  it('counts local calendar days, also across the change to winter time', () => {
    expect(day('2026-10-26') - day('2026-10-24')).toBe(2);
    expect(dayOf(new Date(2026, 9, 25, 23, 59, 59))).toBe(day('2026-10-25'));
    expect(dayOf(new Date(2026, 9, 25, 0, 0, 1))).toBe(day('2026-10-25'));
    expect(dayOf(endOf('2026-10-03'))).toBe(day('2026-10-03'));
    expect(isoDateOfDay(day('2026-02-28') + 1)).toBe('2026-03-01');
    expect(dateOfDay(day('2026-10-03'))).toEqual(new Date(2026, 9, 3));
  });

  it('stores a due date like the TODO dialog: 23:59:59 local time', () => {
    expect(dueDateOfDay(day('2026-10-03'))).toBe(endOf('2026-10-03'));
  });

  it('reads only plain ISO dates', () => {
    expect(dayOfIsoDate('')).toBeNull();
    expect(dayOfIsoDate('3.10.2026')).toBeNull();
    expect(isoDateOfDay(day('2026-10-03'))).toBe('2026-10-03');
  });
});

describe('buildAxis', () => {
  const today = day('2026-09-24');

  it('runs from today or the earliest date to the target date or the latest date, plus a margin', () => {
    const axis = buildAxis({ today, days: [day('2026-09-20'), day('2026-10-20')], target: day('2026-10-15') }, W);
    expect(axis.startDay).toBeLessThan(day('2026-09-20'));
    expect(axis.endDay).toBeGreaterThan(day('2026-10-20'));
    expect(axis.days).toBe(axis.endDay - axis.startDay + 1);
    expect(axis.today).toBe(today);
  });

  it('reaches the target date and today even when every TODO lies between them', () => {
    const axis = buildAxis({ today, days: [day('2026-09-26')], target: day('2026-11-30') }, W);
    expect(axis.startDay).toBeLessThan(today);
    expect(axis.endDay).toBeGreaterThan(day('2026-11-30'));
  });

  it('spans at least two weeks from today when nothing is dated yet', () => {
    const axis = buildAxis({ today, days: [], target: null }, W);
    expect(axis.days).toBeGreaterThanOrEqual(14);
    expect(axis.startDay).toBeLessThan(today);
    expect(axis.endDay).toBeGreaterThanOrEqual(today + 13);
  });

  it('draws a grid by day, week or month depending on the span', () => {
    expect(buildAxis({ today, days: [], target: day('2026-10-08') }, W).scale).toBe('day');
    expect(buildAxis({ today, days: [], target: day('2026-12-20') }, W).scale).toBe('week');
    expect(buildAxis({ today, days: [], target: day('2027-12-31') }, W).scale).toBe('month');
  });

  it('puts grid lines on every day, on Mondays or on the first of a month', () => {
    const daily = buildAxis({ today, days: [], target: day('2026-10-08') }, W);
    expect(daily.ticks).toHaveLength(daily.days);

    const weekly = buildAxis({ today, days: [], target: day('2026-12-20') }, W);
    expect(weekly.ticks.length).toBeGreaterThan(8);
    for (const tick of weekly.ticks) expect(dateOfDay(tick).getDay()).toBe(1);

    const monthly = buildAxis({ today, days: [], target: day('2027-12-31') }, W);
    expect(monthly.ticks.length).toBeGreaterThan(12);
    for (const tick of monthly.ticks) expect(dateOfDay(tick).getDate()).toBe(1);
  });

  it('labels the months above a day or week grid and the years above a month grid', () => {
    const weekly = buildAxis({ today, days: [], target: day('2026-12-20') }, W);
    expect(weekly.bands[0]).toBe(weekly.startDay);
    expect(weekly.bands).toContain(day('2026-10-01'));
    expect(weekly.bands).toContain(day('2026-12-01'));

    const monthly = buildAxis({ today, days: [], target: day('2027-12-31') }, W);
    expect(monthly.bands[0]).toBe(monthly.startDay);
    expect(monthly.bands).toContain(day('2027-01-01'));
    expect(monthly.bands).not.toContain(day('2026-10-01'));
    for (const band of monthly.bands.slice(1)) expect(isoDateOfDay(band).slice(5)).toBe('01-01');
  });

  it('axisFor takes today, the due dates, the milestones and the target date', () => {
    const axis = axisFor({
      todos: [todo('a', { dueDate: endOf('2026-11-10') }), todo('b', { dueDate: endOf('2027-02-01'), status: 'ARCHIVED' })],
      milestones: [milestone('m1', 'Beta', '2026-09-10')],
      targetDate: endOf('2026-10-15'),
      today,
    }, W);
    expect(axis.startDay).toBeLessThan(day('2026-09-10'));
    expect(axis.endDay).toBeGreaterThan(day('2026-11-10'));
    // archivierte TODOs stehen nicht auf dem Zeitstrahl und ziehen ihn nicht in die Länge
    expect(axis.endDay).toBeLessThan(day('2027-02-01'));
  });
});

describe('x and date', () => {
  const axis = buildAxis({ today: day('2026-09-24'), days: [], target: day('2026-10-15') }, W);
  const column = W / axis.days;

  it('maps every day to the middle of its column and back', () => {
    for (let d = axis.startDay; d <= axis.endDay; d++) {
      expect(xOfDay(axis, d, W)).toBeCloseTo((d - axis.startDay + 0.5) * column);
      expect(dayAtX(axis, xOfDay(axis, d, W), W)).toBe(d);
    }
  });

  it('snaps anywhere inside a day column to that day', () => {
    const d = day('2026-10-01');
    const left = xOfDay(axis, d, W) - column / 2;
    expect(dayAtX(axis, left + 0.1, W)).toBe(d);
    expect(dayAtX(axis, left + column - 0.1, W)).toBe(d);
  });

  it('keeps drops beyond the ends on the first and the last day', () => {
    expect(dayAtX(axis, -50, W)).toBe(axis.startDay);
    expect(dayAtX(axis, W + 500, W)).toBe(axis.endDay);
  });
});

describe('stackChips', () => {
  it('stacks chips that would overlap and keeps distant ones on one lane', () => {
    const { slots, lanes } = stackChips([{ id: 'a', x: 100 }, { id: 'b', x: 110 }, { id: 'c', x: 400 }], W);
    expect(Object.fromEntries(slots.map((slot) => [slot.id, slot.lane]))).toEqual({ a: 0, b: 1, c: 0 });
    expect(lanes).toBe(2);
  });

  it('reuses a lane once the chip before it has ended', () => {
    const { slots } = stackChips([{ id: 'a', x: 100 }, { id: 'b', x: 110 }, { id: 'c', x: 100 + CHIP_WIDTH + 20 }], W);
    expect(slots.find((slot) => slot.id === 'c')!.lane).toBe(0);
  });

  it('puts the status dot on the date and flips a chip at the right edge', () => {
    const { slots } = stackChips([{ id: 'a', x: 100 }, { id: 'z', x: W - 10 }], W);
    const a = slots.find((slot) => slot.id === 'a')!;
    const z = slots.find((slot) => slot.id === 'z')!;
    expect(a.flipped).toBe(false);
    expect(a.left + a.anchor).toBe(100);
    expect(z.flipped).toBe(true);
    expect(z.left + CHIP_WIDTH).toBeLessThanOrEqual(W);
    expect(z.left + z.anchor).toBe(W - 10);
  });

  it('has one lane for an empty row', () => {
    expect(stackChips([], W)).toEqual({ slots: [], lanes: 1 });
  });
});

describe('buildRows', () => {
  const axis = buildAxis({ today: day('2026-09-24'), days: [], target: day('2026-10-15') }, W);
  const beta = milestone('m-beta', 'Beta', '2026-10-01');
  const rc = milestone('m-rc', 'RC', '2026-10-08');

  it('has a row per milestone by date and one without milestone; undated TODOs are unplanned', () => {
    const { rows, unplanned } = buildRows([
      todo('a', { dueDate: endOf('2026-09-30'), milestoneId: 'm-beta' }),
      todo('b', { dueDate: endOf('2026-10-05') }),
      todo('c', { milestoneId: 'm-rc' }),
      todo('d', { dueDate: endOf('2026-10-02'), milestoneId: 'deleted-meanwhile' }),
      todo('e', { dueDate: endOf('2026-10-02'), status: 'ARCHIVED' }),
      todo('f', { status: 'ARCHIVED' }),
    ], [rc, beta], axis, W);

    expect(rows.map((row) => row.key)).toEqual(['m-beta', 'm-rc', NO_MILESTONE]);
    expect(rows.map((row) => row.milestone?.title ?? null)).toEqual(['Beta', 'RC', null]);
    expect(rows.map((row) => row.items.map((item) => item.todo.id))).toEqual([['a'], [], ['d', 'b']]);
    expect(unplanned.map((item) => item.id)).toEqual(['c']);
    const a = rows[0]!.items[0]!;
    expect(a.day).toBe(day('2026-09-30'));
    expect(a.left + a.anchor).toBeCloseTo(xOfDay(axis, day('2026-09-30'), W));
  });

  it('counts the lanes of every row', () => {
    const { rows } = buildRows([
      todo('a', { dueDate: endOf('2026-10-05') }),
      todo('b', { dueDate: endOf('2026-10-05') }),
      todo('c', { dueDate: endOf('2026-10-05') }),
    ], [], axis, W);
    expect(rows).toHaveLength(1);
    expect(rows[0]!.lanes).toBe(3);
  });
});

describe('dropping a TODO', () => {
  const dated = { dueDate: endOf('2026-10-01'), milestoneId: 'm-beta' as string | null };

  it('reads the drop target from the droppable id', () => {
    expect(dropTargetOf('unplanned')).toEqual({ kind: 'unplanned' });
    expect(dropTargetOf(`row:${NO_MILESTONE}`)).toEqual({ kind: 'row', milestoneId: null });
    expect(dropTargetOf('row:m-beta')).toEqual({ kind: 'row', milestoneId: 'm-beta' });
    expect(dropTargetOf('todo:a')).toBeNull();
  });

  it('another day in the same row only moves the date', () => {
    expect(planChanges(dated, { kind: 'row', milestoneId: 'm-beta' }, day('2026-10-03')))
      .toEqual({ dueDate: endOf('2026-10-03') });
  });

  it('another row on the same day only changes the milestone', () => {
    expect(planChanges(dated, { kind: 'row', milestoneId: 'm-rc' }, day('2026-10-01'))).toEqual({ milestoneId: 'm-rc' });
    expect(planChanges(dated, { kind: 'row', milestoneId: null }, day('2026-10-01'))).toEqual({ milestoneId: '' });
  });

  it('an unplanned TODO dropped into a row gets the day and the row', () => {
    expect(planChanges({ dueDate: null, milestoneId: null }, { kind: 'row', milestoneId: 'm-rc' }, day('2026-10-06')))
      .toEqual({ dueDate: endOf('2026-10-06'), milestoneId: 'm-rc' });
  });

  it('back into "unplanned" clears the date and keeps the milestone', () => {
    expect(planChanges(dated, { kind: 'unplanned' }, null)).toEqual({ dueDate: '' });
    expect(planChanges({ dueDate: null, milestoneId: 'm-beta' }, { kind: 'unplanned' }, null)).toEqual({});
  });

  it('writes the changes into the TODO rows for the optimistic update', () => {
    const rows = [todo('a', dated), todo('b')];
    const next = applyPlan(rows, 'a', { dueDate: '', milestoneId: 'm-rc' });
    expect(next[0]).toMatchObject({ dueDate: null, milestoneId: 'm-rc' });
    expect(next[1]).toBe(rows[1]);
    expect(applyPlan(rows, 'a', { milestoneId: '' })[0]).toMatchObject({ dueDate: dated.dueDate, milestoneId: null });
  });
});

describe('isOverdue', () => {
  const today = day('2026-09-24');

  it('is overdue from the day after the due date, unless the TODO is done', () => {
    expect(isOverdue({ dueDate: endOf('2026-09-23'), status: 'OPEN' }, today)).toBe(true);
    expect(isOverdue({ dueDate: endOf('2026-09-24'), status: 'IN_PROGRESS' }, today)).toBe(false);
    expect(isOverdue({ dueDate: endOf('2026-09-23'), status: 'DONE' }, today)).toBe(false);
    expect(isOverdue({ dueDate: null, status: 'OPEN' }, today)).toBe(false);
  });
});
