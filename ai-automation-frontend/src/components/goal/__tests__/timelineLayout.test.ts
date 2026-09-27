import { describe, it, expect } from 'vitest';
import {
  CHIP_WIDTH, FALLBACK_WIDTH, NO_MILESTONE, NO_PROJECT_BAND,
  applyPlan, axisFor, buildAxis, buildBands, dateOfDay, dayAtX, dayOf, dayOfIsoDate, dropTargetOf, dueDateOfDay,
  isOverdue, isoDateOfDay, milestoneAccepts, milestoneDateChange, pillDropId, planChanges, rowDropId, stackChips,
  stripDropId, todayLabelPlacement, todoAccepts, trayDropId, undatedDropId, xOfDay,
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

function milestone(id: string, title: string, iso: string | null, overrides: Partial<GoalMilestone> = {}): GoalMilestone {
  return {
    id, goalId: 'g1', title, dueDate: iso ? endOf(iso) : null, projectId: 'p1', position: 0, openQuestions: 0,
    state: 'OPEN', total: 0, done: 0, reachedAt: null, reopened: false,
    createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z', ...overrides,
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

  it('axisFor leaves sub-goals without a date out', () => {
    const axis = axisFor({ todos: [], milestones: [milestone('m1', 'Someday', null)], targetDate: null, today }, W);
    expect(axis).toEqual(buildAxis({ today, days: [], target: null }, W));
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

describe('buildBands (c60bf914)', () => {
  const axis = buildAxis({ today: day('2026-09-24'), days: [], target: day('2026-10-15') }, W);
  const projects = [{ projectId: 'p1', name: 'AutoPilot' }, { projectId: 'p2', name: 'Site' }];
  const beta = milestone('m-beta', 'Beta', '2026-10-01');
  const rc = milestone('m-rc', 'RC', '2026-10-08');
  const docs = milestone('m-docs', 'Docs', null, { position: 1 });
  const wording = milestone('m-wording', 'Wording', null, { position: 0 });
  const promo = milestone('m-promo', 'Promo', '2026-10-03', { projectId: 'p2' });

  it('has a band per project in the goal\'s order, even an empty one', () => {
    const bands = buildBands([], [], projects, axis, W);
    expect(bands.map((band) => [band.key, band.name, band.linked])).toEqual([['p1', 'AutoPilot', true], ['p2', 'Site', true]]);
    // every band has its row "no sub-goal" to drop into
    expect(bands.map((band) => band.rows.map((row) => row.key))).toEqual([[NO_MILESTONE], [NO_MILESTONE]]);
  });

  it('gives dated sub-goals a row by date, puts undated ones into the tray by position, each in its project\'s band', () => {
    const bands = buildBands([], [rc, docs, beta, promo, wording], projects, axis, W);
    const [autopilot, site] = bands;
    expect(autopilot!.dated.map((m) => m.id)).toEqual(['m-beta', 'm-rc']);
    expect(autopilot!.undated.map((m) => m.id)).toEqual(['m-wording', 'm-docs']);
    expect(autopilot!.rows.map((row) => row.key)).toEqual(['m-beta', 'm-rc', NO_MILESTONE]);
    expect(site!.rows.map((row) => row.key)).toEqual(['m-promo', NO_MILESTONE]);
    expect(site!.rows.every((row) => row.band === 'p2')).toBe(true);
  });

  it('places TODOs in the row of their sub-goal, else in "no sub-goal" of their project – dated on the axis, undated beside it', () => {
    const bands = buildBands([
      todo('a', { dueDate: endOf('2026-09-30'), milestoneId: 'm-beta' }),
      todo('b', { dueDate: endOf('2026-10-05') }),
      todo('c', { milestoneId: 'm-rc' }),
      todo('d', { dueDate: endOf('2026-10-02'), milestoneId: 'deleted-meanwhile' }),
      todo('e', { dueDate: endOf('2026-10-02'), status: 'ARCHIVED' }),
      todo('f', { status: 'ARCHIVED' }),
      todo('g', { dueDate: endOf('2026-10-04'), projectId: 'p2', projectName: 'Site' }),
    ], [rc, beta, promo], projects, axis, W);

    const [autopilot, site] = bands;
    expect(autopilot!.rows.map((row) => [row.key, row.items.map((item) => item.todo.id)]))
      .toEqual([['m-beta', ['a']], ['m-rc', []], [NO_MILESTONE, ['d', 'b']]]);
    // the undated TODO keeps its sub-goal: it stands beside the axis in the row of "RC"
    expect(autopilot!.rows.map((row) => [row.key, row.undated.map((item) => item.id)]))
      .toEqual([['m-beta', []], ['m-rc', ['c']], [NO_MILESTONE, []]]);
    expect(site!.rows.map((row) => [row.key, row.items.map((item) => item.todo.id)]))
      .toEqual([['m-promo', []], [NO_MILESTONE, ['g']]]);
    const a = autopilot!.rows[0]!.items[0]!;
    expect(a.day).toBe(day('2026-09-30'));
    expect(a.left + a.anchor).toBeCloseTo(xOfDay(axis, day('2026-09-30'), W));
  });

  it('gives an undated sub-goal a row as soon as it has TODOs, dated or not; without TODOs it only waits in the tray', () => {
    const bands = buildBands([
      todo('a', { dueDate: endOf('2026-10-02'), milestoneId: 'm-docs' }),
      todo('b', { milestoneId: 'm-wording' }),
      todo('c', { milestoneId: 'm-wording', status: 'ARCHIVED' }),
    ], [docs, wording, milestone('m-empty', 'Empty', null, { position: 3 })], projects, axis, W);
    expect(bands[0]!.rows.map((row) => row.key)).toEqual(['m-wording', 'm-docs', NO_MILESTONE]);
    expect(bands[0]!.rows[0]!.undated.map((item) => item.id)).toEqual(['b']);
    expect(bands[0]!.undated.map((m) => m.id)).toEqual(['m-wording', 'm-docs', 'm-empty']);
  });

  it('keeps undated TODOs without sub-goal in "no sub-goal" of their band – there is no global pile any more', () => {
    const bands = buildBands([
      todo('a'), todo('b', { projectId: 'p2', projectName: 'Site' }), todo('c', { projectId: null, projectName: null }),
    ], [], projects, axis, W);
    expect(bands.map((band) => [band.key, band.rows.at(-1)!.undated.map((item) => item.id)]))
      .toEqual([['p1', ['a']], ['p2', ['b']], [NO_PROJECT_BAND, ['c']]]);
  });

  it('adds a band for old data from a project outside the goal and one "without project" when something is there', () => {
    const bands = buildBands([
      todo('a', { dueDate: endOf('2026-10-02'), projectId: 'p9', projectName: 'Elsewhere' }),
      todo('b', { dueDate: endOf('2026-10-03'), projectId: null, projectName: null }),
    ], [milestone('m-lost', 'Lost', null, { projectId: null })], projects, axis, W);
    expect(bands.map((band) => [band.key, band.name, band.linked])).toEqual([
      ['p1', 'AutoPilot', true], ['p2', 'Site', true], ['p9', 'Elsewhere', false], [NO_PROJECT_BAND, null, false],
    ]);
    expect(bands[3]!.undated.map((m) => m.id)).toEqual(['m-lost']);
    expect(bands[3]!.rows.at(-1)!.items.map((item) => item.todo.id)).toEqual(['b']);
  });

  it('counts the lanes of every row', () => {
    const bands = buildBands([
      todo('a', { dueDate: endOf('2026-10-05') }),
      todo('b', { dueDate: endOf('2026-10-05') }),
      todo('c', { dueDate: endOf('2026-10-05') }),
    ], [], projects.slice(0, 1), axis, W);
    expect(bands[0]!.rows).toHaveLength(1);
    expect(bands[0]!.rows[0]!.lanes).toBe(3);
  });
});

describe('drop targets', () => {
  it('reads the drop target from the droppable id', () => {
    expect(dropTargetOf(undatedDropId('p1', NO_MILESTONE))).toEqual({ kind: 'undated', band: 'p1', milestoneId: null });
    expect(dropTargetOf(undatedDropId('p1', 'm-beta'))).toEqual({ kind: 'undated', band: 'p1', milestoneId: 'm-beta' });
    expect(dropTargetOf('unplanned')).toBeNull();
    expect(dropTargetOf(rowDropId('p1', NO_MILESTONE))).toEqual({ kind: 'row', band: 'p1', milestoneId: null });
    expect(dropTargetOf(rowDropId('p1', 'm-beta'))).toEqual({ kind: 'row', band: 'p1', milestoneId: 'm-beta' });
    expect(dropTargetOf(stripDropId('p2'))).toEqual({ kind: 'strip', band: 'p2' });
    expect(dropTargetOf(trayDropId('p2'))).toEqual({ kind: 'tray', band: 'p2' });
    expect(dropTargetOf(pillDropId('p2', 'm-docs'))).toEqual({ kind: 'pill', band: 'p2', milestoneId: 'm-docs' });
    expect(dropTargetOf('todo:a')).toBeNull();
    expect(dropTargetOf('row:m-beta')).toBeNull();
    expect(dropTargetOf('strip:p1:x')).toBeNull();
  });

  it('offers a TODO only the rows, undated areas and sub-goals of its own project', () => {
    const own = { projectId: 'p1', milestoneId: null };
    expect(todoAccepts(own, { kind: 'row', band: 'p1', milestoneId: 'm-beta' })).toBe(true);
    expect(todoAccepts(own, { kind: 'row', band: 'p1', milestoneId: null })).toBe(true);
    expect(todoAccepts(own, { kind: 'pill', band: 'p1', milestoneId: 'm-docs' })).toBe(true);
    expect(todoAccepts(own, { kind: 'undated', band: 'p1', milestoneId: 'm-beta' })).toBe(true);
    expect(todoAccepts(own, { kind: 'undated', band: 'p2', milestoneId: null })).toBe(false);
    expect(todoAccepts(own, { kind: 'row', band: 'p2', milestoneId: 'm-promo' })).toBe(false);
    expect(todoAccepts(own, { kind: 'row', band: 'p2', milestoneId: null })).toBe(false);
    expect(todoAccepts(own, { kind: 'strip', band: 'p1' })).toBe(false);
    expect(todoAccepts(own, { kind: 'tray', band: 'p1' })).toBe(false);
    // TODOs without a project only go into the band "without project"
    expect(todoAccepts({ projectId: null, milestoneId: null }, { kind: 'row', band: NO_PROJECT_BAND, milestoneId: null })).toBe(true);
    expect(todoAccepts({ projectId: null, milestoneId: null }, { kind: 'row', band: 'p1', milestoneId: null })).toBe(false);
  });

  it('lets an old TODO stay in its sub-goal of another project and move its day there', () => {
    const legacy = { projectId: 'p2', milestoneId: 'm-beta' };
    expect(todoAccepts(legacy, { kind: 'row', band: 'p1', milestoneId: 'm-beta' })).toBe(true);
    expect(todoAccepts(legacy, { kind: 'row', band: 'p1', milestoneId: 'm-rc' })).toBe(false);
    expect(todoAccepts(legacy, { kind: 'row', band: 'p2', milestoneId: null })).toBe(true);
  });

  it('keeps a sub-goal in its band: strip and rows set the date, the tray takes it away', () => {
    const own = { projectId: 'p1' };
    expect(milestoneAccepts(own, { kind: 'strip', band: 'p1' })).toBe(true);
    expect(milestoneAccepts(own, { kind: 'row', band: 'p1', milestoneId: 'm-rc' })).toBe(true);
    expect(milestoneAccepts(own, { kind: 'tray', band: 'p1' })).toBe(true);
    expect(milestoneAccepts(own, { kind: 'strip', band: 'p2' })).toBe(false);
    expect(milestoneAccepts(own, { kind: 'pill', band: 'p1', milestoneId: 'm-docs' })).toBe(false);
    expect(milestoneAccepts(own, { kind: 'undated', band: 'p1', milestoneId: null })).toBe(false);
  });

  it('dates a sub-goal where it lands, clears the date in the tray and changes nothing on the same day', () => {
    const undated = { dueDate: null };
    const dated = { dueDate: endOf('2026-10-01') };
    expect(milestoneDateChange(undated, { kind: 'strip', band: 'p1' }, day('2026-10-06'))).toBe(endOf('2026-10-06'));
    expect(milestoneDateChange(dated, { kind: 'row', band: 'p1', milestoneId: null }, day('2026-10-03'))).toBe(endOf('2026-10-03'));
    expect(milestoneDateChange(dated, { kind: 'strip', band: 'p1' }, day('2026-10-01'))).toBeUndefined();
    expect(milestoneDateChange(dated, { kind: 'tray', band: 'p1' }, null)).toBe('');
    expect(milestoneDateChange(undated, { kind: 'tray', band: 'p1' }, null)).toBeUndefined();
  });
});

describe('dropping a TODO', () => {
  const dated = { dueDate: endOf('2026-10-01'), milestoneId: 'm-beta' as string | null };
  const row = (milestoneId: string | null) => ({ kind: 'row' as const, band: 'p1', milestoneId });

  it('another day in the same row only moves the date', () => {
    expect(planChanges(dated, row('m-beta'), day('2026-10-03'))).toEqual({ dueDate: endOf('2026-10-03') });
  });

  it('another row on the same day only changes the sub-goal', () => {
    expect(planChanges(dated, row('m-rc'), day('2026-10-01'))).toEqual({ milestoneId: 'm-rc' });
    expect(planChanges(dated, row(null), day('2026-10-01'))).toEqual({ milestoneId: '' });
  });

  it('an unplanned TODO dropped into a row gets the day and the row', () => {
    expect(planChanges({ dueDate: null, milestoneId: null }, row('m-rc'), day('2026-10-06')))
      .toEqual({ dueDate: endOf('2026-10-06'), milestoneId: 'm-rc' });
  });

  it('on a sub-goal of the tray it only changes the sub-goal, the day stays', () => {
    expect(planChanges(dated, { kind: 'pill', band: 'p1', milestoneId: 'm-docs' }, null)).toEqual({ milestoneId: 'm-docs' });
    expect(planChanges(dated, { kind: 'pill', band: 'p1', milestoneId: 'm-beta' }, null)).toEqual({});
  });

  it('into the undated area of a row it clears the date and takes that row\'s sub-goal', () => {
    const undated = (milestoneId: string | null) => ({ kind: 'undated' as const, band: 'p1', milestoneId });
    expect(planChanges(dated, undated('m-beta'), null)).toEqual({ dueDate: '' });
    expect(planChanges(dated, undated('m-rc'), null)).toEqual({ dueDate: '', milestoneId: 'm-rc' });
    expect(planChanges({ dueDate: null, milestoneId: 'm-beta' }, undated('m-beta'), null)).toEqual({});
    expect(planChanges({ dueDate: null, milestoneId: 'm-beta' }, undated(null), null)).toEqual({ milestoneId: '' });
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

// ff6f08fd: at 768 px the label "today" lay on the diamond of the next day
describe('todayLabelPlacement', () => {
  const width = 480;

  it('stays centred over today while nothing is near', () => {
    expect(todayLabelPlacement(200, [], width)).toBe('center');
    expect(todayLabelPlacement(200, [120, 290], width)).toBe('center');
  });

  it('moves away from a diamond or the target flag next to today', () => {
    expect(todayLabelPlacement(200, [216], width)).toBe('left');
    expect(todayLabelPlacement(200, [184], width)).toBe('right');
  });

  it('goes up to the day numbers when both sides are taken', () => {
    expect(todayLabelPlacement(200, [184, 216], width)).toBe('raised');
    expect(todayLabelPlacement(200, [200], width)).toBe('raised');
  });

  it('stays inside the axis at its ends', () => {
    expect(todayLabelPlacement(10, [], width)).toBe('right');
    expect(todayLabelPlacement(width - 10, [], width)).toBe('left');
  });
});
