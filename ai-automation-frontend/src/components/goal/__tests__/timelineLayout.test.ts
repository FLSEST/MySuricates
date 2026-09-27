import { describe, it, expect } from 'vitest';
import {
  BAR_ZONE, CHIP_WIDTH, FALLBACK_WIDTH, LANE_HEIGHT, MILESTONES_DROP_ID, NO_PACKAGE, NO_PROJECT_BAND, WEEK_DAYS,
  applyPlan, axisFor, barBox, barLabelFit, buildAxis, buildBands, dateOfDay, dayAtX, dayOf, dayOfIsoDate, dayShift,
  dropTargetOf, dueDateOfDay, hasDays, isOverdue, isoDateOfDay, milestoneAccepts, milestoneMarks, movedRange,
  packageAccepts, packageRange, pillDropId, planChanges, rangeRequest, resizedRange, rowDropId, sameRange, stackChips,
  startDateOfDay, stripDropId, todayLabelPlacement, todoAccepts, trayDropId, undatedDropId, weekFrom, xOfDay,
} from '../timelineLayout';
import type { GoalMilestone, GoalTodoItem, GoalWorkPackage } from '../../../types';

const day = (iso: string) => dayOfIsoDate(iso)!;
/** Ende des lokalen Tages – so speichert der TODO-Dialog eine Fälligkeit, so endet ein Paket. */
const endOf = (iso: string) => new Date(`${iso}T23:59:59`).toISOString();
/** Anfang des lokalen Tages – so beginnt ein Paket. */
const startOf = (iso: string) => new Date(`${iso}T00:00:00`).toISOString();
const W = FALLBACK_WIDTH;

function todo(id: string, overrides: Partial<GoalTodoItem> = {}): GoalTodoItem {
  return {
    id, title: `Todo ${id}`, status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
    goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null,
    openQuestion: null, updatedAt: '2026-09-23T00:00:00Z', dueDate: null, workPackageId: null, ...overrides,
  };
}

/** Ein Arbeitspaket; `from`/`to` als `YYYY-MM-DD`, ohne = ohne Zeitraum. */
function wp(id: string, title: string, from: string | null, to: string | null, overrides: Partial<GoalWorkPackage> = {}): GoalWorkPackage {
  return {
    id, goalId: 'g1', projectId: 'p1', title, startDate: from ? startOf(from) : null, endDate: to ? endOf(to) : null,
    position: 0, state: 'OPEN', total: 0, done: 0, openQuestions: 0, completedAt: null, reopened: false,
    createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z', ...overrides,
  };
}

function milestone(id: string, title: string, iso: string, overrides: Partial<GoalMilestone> = {}): GoalMilestone {
  return {
    id, goalId: 'g1', title, dueDate: endOf(iso), state: 'OPEN', workPackageIds: [], packagesTotal: 0, packagesDone: 0,
    reachedAt: null, reopened: false, createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z', ...overrides,
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

  // c60bf914: with nothing dated the timeline shows today and the four weeks after
  it('spans today to four weeks ahead when nothing is dated, at least two weeks otherwise', () => {
    const empty = buildAxis({ today, days: [], target: null }, W);
    expect(empty.startDay).toBeLessThan(today);
    expect(empty.endDay).toBeGreaterThanOrEqual(today + 27);
    expect(empty.endDay).toBeLessThan(today + 35);
    const short = buildAxis({ today, days: [today + 2], target: null }, W);
    expect(short.endDay).toBeGreaterThanOrEqual(today + 13);
    expect(short.endDay).toBeLessThan(today + 20);
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

  it('axisFor takes today, start and end of the packages, the milestones, the due dates and the target date', () => {
    const axis = axisFor({
      todos: [todo('a', { dueDate: endOf('2026-11-10') }), todo('b', { dueDate: endOf('2027-02-01'), status: 'ARCHIVED' })],
      workPackages: [wp('w1', 'Repo', '2026-09-05', '2026-09-12'), wp('w2', 'Someday', null, null)],
      milestones: [milestone('m1', 'Freeze', '2026-11-20')],
      targetDate: endOf('2026-10-15'),
      today,
    }, W);
    expect(axis.startDay).toBeLessThan(day('2026-09-05'));
    expect(axis.endDay).toBeGreaterThan(day('2026-11-20'));
    // archivierte TODOs stehen nicht auf dem Zeitstrahl und ziehen ihn nicht in die Länge
    expect(axis.endDay).toBeLessThan(day('2027-02-01'));
  });

  it('axisFor leaves packages without a time span out', () => {
    const axis = axisFor({ todos: [], workPackages: [wp('w1', 'Someday', null, null)], milestones: [], targetDate: null, today }, W);
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

describe('time spans (c60bf914)', () => {
  const axis = buildAxis({ today: day('2026-09-24'), days: [], target: day('2026-10-15') }, W);
  const perDay = W / axis.days;

  it('reads a package\'s span in local days – only with start and end, turned round if the two are swapped', () => {
    expect(packageRange(wp('w', 'Repo', '2026-10-12', '2026-10-18'))).toEqual({ start: day('2026-10-12'), end: day('2026-10-18') });
    expect(packageRange(wp('w', 'One day', '2026-10-12', '2026-10-12'))).toEqual({ start: day('2026-10-12'), end: day('2026-10-12') });
    expect(packageRange(wp('w', 'Swapped', '2026-10-18', '2026-10-12'))).toEqual({ start: day('2026-10-12'), end: day('2026-10-18') });
    expect(packageRange(wp('w', 'Open', null, null))).toBeNull();
    expect(packageRange({ startDate: startOf('2026-10-12'), endDate: null })).toBeNull();
  });

  it('sends start at 00:00 and end at 23:59:59 local time, or both empty to clear the span', () => {
    expect(startDateOfDay(day('2026-10-12'))).toBe(startOf('2026-10-12'));
    expect(rangeRequest({ start: day('2026-10-12'), end: day('2026-10-18') }))
      .toEqual({ startDate: startOf('2026-10-12'), endDate: endOf('2026-10-18') });
    expect(rangeRequest(null)).toEqual({ startDate: '', endDate: '' });
  });

  it('moves a span keeping its length, stretches either end but never past the other one', () => {
    const range = { start: day('2026-10-12'), end: day('2026-10-18') };
    expect(movedRange(range, 3)).toEqual({ start: day('2026-10-15'), end: day('2026-10-21') });
    expect(movedRange(range, -2)).toEqual({ start: day('2026-10-10'), end: day('2026-10-16') });
    expect(resizedRange(range, 'end', 2)).toEqual({ start: day('2026-10-12'), end: day('2026-10-20') });
    expect(resizedRange(range, 'start', -1)).toEqual({ start: day('2026-10-11'), end: day('2026-10-18') });
    // at least one day: the ends do not cross
    expect(resizedRange(range, 'end', -30)).toEqual({ start: day('2026-10-12'), end: day('2026-10-12') });
    expect(resizedRange(range, 'start', 30)).toEqual({ start: day('2026-10-18'), end: day('2026-10-18') });
    expect(sameRange(range, { ...range })).toBe(true);
    expect(sameRange(range, null)).toBe(false);
    expect(sameRange(null, null)).toBe(true);
  });

  it('gives a package dropped from the tray one week from the day it lands on', () => {
    expect(WEEK_DAYS).toBe(7);
    expect(weekFrom(day('2026-10-12'))).toEqual({ start: day('2026-10-12'), end: day('2026-10-18') });
  });

  it('turns a pointer movement into whole days', () => {
    expect(dayShift(axis, perDay * 2.4, W)).toBe(2);
    expect(dayShift(axis, perDay * 2.6, W)).toBe(3);
    expect(dayShift(axis, -perDay * 1.2, W)).toBe(-1);
    expect(dayShift(axis, perDay * 0.3, W)).toBe(0);
  });

  it('draws a bar from the start of its first day to the end of its last one', () => {
    const range = { start: day('2026-10-01'), end: day('2026-10-07') };
    const box = barBox(axis, range, W);
    expect(box.left).toBeCloseTo((day('2026-10-01') - axis.startDay) * perDay);
    expect(box.width).toBeCloseTo(7 * perDay);
    expect(box.left).toBeLessThan(xOfDay(axis, range.start, W));
    expect(box.left + box.width).toBeGreaterThan(xOfDay(axis, range.end, W));
  });

  it('puts title and progress into a wide bar, only the title into a narrower one and the title beside a short bar', () => {
    expect(barLabelFit('Repo sauber', 200)).toBe('full');
    expect(barLabelFit('Repo sauber', 90)).toBe('title');
    expect(barLabelFit('Repo sauber', 40)).toBe('none');
  });
});

describe('milestoneMarks (c60bf914)', () => {
  const axis = buildAxis({ today: day('2026-09-24'), days: [], target: day('2026-10-31') }, W);

  it('sets each diamond on its day, by date, and moves a title that would cover the next one to a second lane', () => {
    const { marks, lanes } = milestoneMarks([
      milestone('m3', 'Repo öffentlich', '2026-10-26'),
      milestone('m1', 'Code-Freeze', '2026-10-10'),
      milestone('m2', 'Werbematerial fertig und abgenommen', '2026-10-13'),
    ], axis, W);
    expect(marks.map((mark) => mark.milestone.id)).toEqual(['m1', 'm2', 'm3']);
    expect(marks[0]!.x).toBeCloseTo(xOfDay(axis, day('2026-10-10'), W));
    expect(marks.map((mark) => mark.lane)).toEqual([0, 1, 0]);
    expect(lanes).toBe(2);
  });

  it('keeps a title inside the axis at the right edge and has one lane without milestones', () => {
    const { marks } = milestoneMarks([milestone('m1', 'Launch day with a long name', '2026-10-31')], axis, W);
    expect(marks[0]!.x + 10 + marks[0]!.labelWidth).toBeLessThanOrEqual(W);
    expect(milestoneMarks([], axis, W)).toEqual({ marks: [], lanes: 1 });
  });
});

describe('buildBands (c60bf914)', () => {
  const axis = buildAxis({ today: day('2026-09-24'), days: [], target: day('2026-10-15') }, W);
  const projects = [{ projectId: 'p1', name: 'AutoPilot' }, { projectId: 'p2', name: 'Site' }];
  const umbau = wp('w-umbau', 'Umbau', '2026-09-20', '2026-10-01', { position: 0 });
  const repo = wp('w-repo', 'Repo sauber', '2026-10-01', '2026-10-09', { position: 1 });
  const github = wp('w-github', 'GitHub-Mechanik', null, null, { position: 2 });
  const halt = wp('w-halt', 'Haltepunkte', null, null, { position: 3 });
  const site = wp('w-site', 'Werbematerial', '2026-10-05', '2026-10-12', { projectId: 'p2' });

  it('has a band per project in the goal\'s order, even an empty one', () => {
    const bands = buildBands([], [], projects, axis, W);
    expect(bands.map((band) => [band.key, band.name, band.linked])).toEqual([['p1', 'AutoPilot', true], ['p2', 'Site', true]]);
    expect(bands.every((band) => band.rows.length === 0 && band.unscheduled.length === 0)).toBe(true);
    expect(bands[0]!.noPackage.key).toBe(NO_PACKAGE);
  });

  it('gives every package with a span one row in package order and puts the others into the tray', () => {
    const bands = buildBands([], [halt, repo, site, github, umbau], projects, axis, W);
    const [autopilot, siteBand] = bands;
    expect(autopilot!.rows.map((row) => row.key)).toEqual(['w-umbau', 'w-repo']);
    expect(autopilot!.rows[0]!.range).toEqual({ start: day('2026-09-20'), end: day('2026-10-01') });
    expect(autopilot!.unscheduled.map((entry) => entry.workPackage.id)).toEqual(['w-github', 'w-halt']);
    expect(siteBand!.rows.map((row) => [row.key, row.band])).toEqual([['w-site', 'p2']]);
  });

  it('places due TODOs under the bar of their package and keeps undated ones as a count at its label – nothing lost', () => {
    const bands = buildBands([
      todo('a', { dueDate: endOf('2026-09-30'), workPackageId: 'w-umbau' }),
      todo('b', { workPackageId: 'w-umbau' }),
      todo('c', { workPackageId: 'w-umbau' }),
      todo('d', { workPackageId: 'w-github' }),
      todo('e', { dueDate: endOf('2026-10-02') }),
      todo('f', { status: 'ARCHIVED', workPackageId: 'w-umbau' }),
      todo('g', { dueDate: endOf('2026-10-04'), workPackageId: 'deleted-meanwhile' }),
    ], [umbau, repo, github], projects, axis, W);
    const [autopilot] = bands;
    const [umbauRow, repoRow] = autopilot!.rows;
    expect(umbauRow!.items.map((item) => item.todo.id)).toEqual(['a']);
    expect(umbauRow!.undated.map((item) => item.id)).toEqual(['b', 'c']);
    expect(umbauRow!.lanes).toBe(1);
    // no chips, a flat row
    expect(repoRow!.lanes).toBe(0);
    expect(autopilot!.unscheduled).toEqual([{ workPackage: github, undated: [todo('d', { workPackageId: 'w-github' })] }]);
    expect(autopilot!.noPackage.items.map((item) => item.todo.id)).toEqual(['e', 'g']);
    const a = umbauRow!.items[0]!;
    expect(a.day).toBe(day('2026-09-30'));
    expect(a.left + a.anchor).toBeCloseTo(xOfDay(axis, day('2026-09-30'), W));
  });

  it('gives a package without a span a row only once one of its TODOs is due – its undated ones then stay there, not in the tray', () => {
    const bands = buildBands([
      todo('a', { dueDate: endOf('2026-10-02'), workPackageId: 'w-github' }),
      todo('b', { workPackageId: 'w-github' }),
    ], [github], projects, axis, W);
    expect(bands[0]!.rows.map((row) => [row.key, row.range, row.undated.map((item) => item.id)])).toEqual([['w-github', null, ['b']]]);
    expect(bands[0]!.unscheduled).toEqual([{ workPackage: github, undated: [] }]);
  });

  it('adds a band for old data from a project outside the goal and one "without project" when something is there', () => {
    const bands = buildBands([
      todo('a', { dueDate: endOf('2026-10-02'), projectId: 'p9', projectName: 'Elsewhere' }),
      todo('b', { projectId: null, projectName: null }),
    ], [wp('w-lost', 'Lost', null, null, { projectId: null })], projects, axis, W);
    expect(bands.map((band) => [band.key, band.name, band.linked])).toEqual([
      ['p1', 'AutoPilot', true], ['p2', 'Site', true], ['p9', 'Elsewhere', false], [NO_PROJECT_BAND, null, false],
    ]);
    expect(bands[3]!.unscheduled.map((entry) => entry.workPackage.id)).toEqual(['w-lost']);
    expect(bands[3]!.noPackage.undated.map((item) => item.id)).toEqual(['b']);
  });

  it('stacks due TODOs of one day into lanes below the bar', () => {
    const bands = buildBands([
      todo('a', { dueDate: endOf('2026-10-05'), workPackageId: 'w-repo' }),
      todo('b', { dueDate: endOf('2026-10-05'), workPackageId: 'w-repo' }),
      todo('c', { dueDate: endOf('2026-10-05'), workPackageId: 'w-repo' }),
    ], [repo], projects.slice(0, 1), axis, W);
    expect(bands[0]!.rows[0]!.lanes).toBe(3);
    expect(BAR_ZONE + 3 * LANE_HEIGHT).toBeGreaterThan(3 * LANE_HEIGHT);
  });
});

describe('drop targets', () => {
  it('reads the drop target from the droppable id', () => {
    expect(dropTargetOf(rowDropId('p1', NO_PACKAGE))).toEqual({ kind: 'row', band: 'p1', workPackageId: null });
    expect(dropTargetOf(rowDropId('p1', 'w-repo'))).toEqual({ kind: 'row', band: 'p1', workPackageId: 'w-repo' });
    expect(dropTargetOf(undatedDropId('p1', NO_PACKAGE))).toEqual({ kind: 'undated', band: 'p1', workPackageId: null });
    expect(dropTargetOf(undatedDropId('p1', 'w-repo'))).toEqual({ kind: 'undated', band: 'p1', workPackageId: 'w-repo' });
    expect(dropTargetOf(stripDropId('p2'))).toEqual({ kind: 'strip', band: 'p2' });
    expect(dropTargetOf(trayDropId('p2'))).toEqual({ kind: 'tray', band: 'p2' });
    expect(dropTargetOf(pillDropId('p2', 'w-site'))).toEqual({ kind: 'pill', band: 'p2', workPackageId: 'w-site' });
    expect(dropTargetOf(MILESTONES_DROP_ID)).toEqual({ kind: 'milestones' });
    expect(dropTargetOf('todo:a')).toBeNull();
    expect(dropTargetOf('unplanned')).toBeNull();
    expect(dropTargetOf('strip:p1:x')).toBeNull();
    expect(hasDays({ kind: 'row', band: 'p1', workPackageId: null })).toBe(true);
    expect(hasDays({ kind: 'milestones' })).toBe(true);
    expect(hasDays({ kind: 'undated', band: 'p1', workPackageId: null })).toBe(false);
    expect(hasDays(null)).toBe(false);
  });

  it('offers a TODO only the rows, labels and packages of its own project', () => {
    const own = { projectId: 'p1', workPackageId: null };
    expect(todoAccepts(own, { kind: 'row', band: 'p1', workPackageId: 'w-repo' })).toBe(true);
    expect(todoAccepts(own, { kind: 'row', band: 'p1', workPackageId: null })).toBe(true);
    expect(todoAccepts(own, { kind: 'undated', band: 'p1', workPackageId: 'w-repo' })).toBe(true);
    expect(todoAccepts(own, { kind: 'pill', band: 'p1', workPackageId: 'w-github' })).toBe(true);
    expect(todoAccepts(own, { kind: 'row', band: 'p2', workPackageId: 'w-site' })).toBe(false);
    expect(todoAccepts(own, { kind: 'undated', band: 'p2', workPackageId: null })).toBe(false);
    expect(todoAccepts(own, { kind: 'strip', band: 'p1' })).toBe(false);
    expect(todoAccepts(own, { kind: 'tray', band: 'p1' })).toBe(false);
    expect(todoAccepts(own, { kind: 'milestones' })).toBe(false);
    // TODOs without a project only go into the band "without project"
    expect(todoAccepts({ projectId: null, workPackageId: null }, { kind: 'row', band: NO_PROJECT_BAND, workPackageId: null })).toBe(true);
    expect(todoAccepts({ projectId: null, workPackageId: null }, { kind: 'row', band: 'p1', workPackageId: null })).toBe(false);
  });

  it('lets an old TODO stay in its package of another project and move its day there', () => {
    const legacy = { projectId: 'p2', workPackageId: 'w-repo' };
    expect(todoAccepts(legacy, { kind: 'row', band: 'p1', workPackageId: 'w-repo' })).toBe(true);
    expect(todoAccepts(legacy, { kind: 'row', band: 'p1', workPackageId: 'w-umbau' })).toBe(false);
    expect(todoAccepts(legacy, { kind: 'row', band: 'p2', workPackageId: null })).toBe(true);
  });

  it('keeps a bar in its band: rows and the head strip take it on a day, the tray takes its span away', () => {
    const own = { projectId: 'p1' };
    expect(packageAccepts(own, { kind: 'row', band: 'p1', workPackageId: 'w-umbau' })).toBe(true);
    expect(packageAccepts(own, { kind: 'strip', band: 'p1' })).toBe(true);
    expect(packageAccepts(own, { kind: 'tray', band: 'p1' })).toBe(true);
    expect(packageAccepts(own, { kind: 'row', band: 'p2', workPackageId: null })).toBe(false);
    expect(packageAccepts(own, { kind: 'undated', band: 'p1', workPackageId: null })).toBe(false);
    expect(packageAccepts(own, { kind: 'pill', band: 'p1', workPackageId: 'w-github' })).toBe(false);
    expect(packageAccepts(own, { kind: 'milestones' })).toBe(false);
  });

  it('lets a milestone land on any day – it belongs to the whole goal', () => {
    expect(milestoneAccepts({ kind: 'milestones' })).toBe(true);
    expect(milestoneAccepts({ kind: 'row', band: 'p2', workPackageId: null })).toBe(true);
    expect(milestoneAccepts({ kind: 'strip', band: 'p1' })).toBe(true);
    expect(milestoneAccepts({ kind: 'tray', band: 'p1' })).toBe(false);
    expect(milestoneAccepts({ kind: 'undated', band: 'p1', workPackageId: null })).toBe(false);
  });
});

describe('dropping a TODO', () => {
  const dated = { dueDate: endOf('2026-10-01'), workPackageId: 'w-repo' as string | null };
  const row = (workPackageId: string | null) => ({ kind: 'row' as const, band: 'p1', workPackageId });

  it('another day in the same row only moves the date', () => {
    expect(planChanges(dated, row('w-repo'), day('2026-10-03'))).toEqual({ dueDate: endOf('2026-10-03') });
  });

  it('another row on the same day only changes the package', () => {
    expect(planChanges(dated, row('w-umbau'), day('2026-10-01'))).toEqual({ workPackageId: 'w-umbau' });
    expect(planChanges(dated, row(null), day('2026-10-01'))).toEqual({ workPackageId: '' });
  });

  it('a TODO without a date dropped onto a row gets the day and the package', () => {
    expect(planChanges({ dueDate: null, workPackageId: null }, row('w-umbau'), day('2026-10-06')))
      .toEqual({ dueDate: endOf('2026-10-06'), workPackageId: 'w-umbau' });
  });

  it('on a package of the tray it only changes the package, the day stays', () => {
    expect(planChanges(dated, { kind: 'pill', band: 'p1', workPackageId: 'w-github' }, null)).toEqual({ workPackageId: 'w-github' });
    expect(planChanges(dated, { kind: 'pill', band: 'p1', workPackageId: 'w-repo' }, null)).toEqual({});
  });

  it('onto the label of a row it clears the date and takes that row\'s package', () => {
    const undated = (workPackageId: string | null) => ({ kind: 'undated' as const, band: 'p1', workPackageId });
    expect(planChanges(dated, undated('w-repo'), null)).toEqual({ dueDate: '' });
    expect(planChanges(dated, undated('w-umbau'), null)).toEqual({ dueDate: '', workPackageId: 'w-umbau' });
    expect(planChanges({ dueDate: null, workPackageId: 'w-repo' }, undated('w-repo'), null)).toEqual({});
    expect(planChanges({ dueDate: null, workPackageId: 'w-repo' }, undated(null), null)).toEqual({ workPackageId: '' });
  });

  it('changes nothing on the head strip, the tray or the milestone row', () => {
    expect(planChanges(dated, { kind: 'strip', band: 'p1' }, day('2026-10-05'))).toEqual({});
    expect(planChanges(dated, { kind: 'tray', band: 'p1' }, null)).toEqual({});
    expect(planChanges(dated, { kind: 'milestones' }, day('2026-10-05'))).toEqual({});
  });

  it('writes the changes into the TODO rows for the optimistic update', () => {
    const rows = [todo('a', dated), todo('b')];
    const next = applyPlan(rows, 'a', { dueDate: '', workPackageId: 'w-umbau' });
    expect(next[0]).toMatchObject({ dueDate: null, workPackageId: 'w-umbau' });
    expect(next[1]).toBe(rows[1]);
    expect(applyPlan(rows, 'a', { workPackageId: '' })[0]).toMatchObject({ dueDate: dated.dueDate, workPackageId: null });
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
