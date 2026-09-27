import { describe, it, expect } from 'vitest';
import { en } from '../../../i18n/en';
import { de } from '../../../i18n/de';
import { dayText, liveLine, rangeText, type LiveContext } from '../dragLabel';
import { dayOfIsoDate } from '../timelineLayout';
import type { Dragged } from '../timelineDrag';
import type { GoalMilestone, GoalTodoItem, GoalWorkPackage } from '../../../types';

const day = (iso: string) => dayOfIsoDate(iso)!;
const endOf = (iso: string) => new Date(`${iso}T23:59:59`).toISOString();

const todo = (overrides: Partial<GoalTodoItem> = {}): GoalTodoItem => ({
  id: 't1', title: 'Lizenzdateien', status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
  goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null,
  openQuestion: null, updatedAt: '', dueDate: null, workPackageId: null, ...overrides,
});

const workPackage: GoalWorkPackage = {
  id: 'w-repo', goalId: 'g1', projectId: 'p1', title: 'Repo sauber', startDate: null, endDate: null, position: 0,
  state: 'OPEN', total: 2, done: 0, openQuestions: 0, completedAt: null, reopened: false, createdAt: '', updatedAt: '',
};

const milestone = { id: 'm1', title: 'Code-Freeze', dueDate: endOf('2026-10-10') } as GoalMilestone;

function context(locale: 'de' | 'en' = 'de'): LiveContext {
  return {
    t: locale === 'de' ? de : en,
    locale,
    packageTitle: (id) => (id === 'w-repo' ? 'Repo sauber' : undefined),
    bandName: (band) => (band === 'p1' ? 'AutoPilot' : 'Site'),
  };
}

const asTodo = (overrides: Partial<GoalTodoItem> = {}): NonNullable<Dragged> => ({ kind: 'todo', todo: todo(overrides) });

// c60bf914, Flo: „beim Reinziehen … unten drunter Arbeitspaket und Datum einblenden“
describe('live line while dragging', () => {
  it('writes days with the weekday and spans with the shared parts once, in the language of the app', () => {
    expect(dayText(day('2026-10-12'), 'de')).toBe('Mo., 12.10.');
    expect(dayText(day('2026-10-12'), 'en')).toBe('Mon, 10/12');
    expect(rangeText({ start: day('2026-10-12'), end: day('2026-10-18') }, 'de')).toBe('12.–18.10.');
    // ICU sets thin spaces around the dash in English
    expect(rangeText({ start: day('2026-10-12'), end: day('2026-10-18') }, 'en').replace(/\s/g, ' ')).toBe('10/12 – 10/18');
    expect(rangeText({ start: day('2026-10-12'), end: day('2026-10-12') }, 'de')).toBe('12.10.');
  });

  it('shows a TODO its package and the day under the pointer', () => {
    expect(liveLine(asTodo(), { kind: 'row', band: 'p1', workPackageId: 'w-repo' }, day('2026-10-12'), null, context()))
      .toEqual({ text: 'Repo sauber · Mo., 12.10.', refused: false, arrow: true });
    expect(liveLine(asTodo(), { kind: 'row', band: 'p1', workPackageId: null }, day('2026-10-12'), null, context('en')))
      .toEqual({ text: 'No work package · Mon, 10/12', refused: false, arrow: true });
  });

  it('says "no date" at the label of a row and keeps the TODO\'s own day on a package of the tray', () => {
    expect(liveLine(asTodo(), { kind: 'undated', band: 'p1', workPackageId: 'w-repo' }, null, null, context()).text)
      .toBe('Repo sauber · ohne Datum');
    expect(liveLine(asTodo({ dueDate: endOf('2026-10-09') }), { kind: 'pill', band: 'p1', workPackageId: 'w-repo' }, null, null, context()).text)
      .toBe('Repo sauber · Fr., 9.10.');
  });

  it('says where a TODO may go when it hangs over another project, and that releasing outside cancels', () => {
    expect(liveLine(asTodo(), { kind: 'row', band: 'p2', workPackageId: null }, day('2026-10-12'), null, context()))
      .toEqual({ text: 'Nicht hier – nur in AutoPilot', refused: true });
    expect(liveLine(asTodo(), null, null, null, context())).toEqual({ text: 'Loslassen bricht ab', refused: true });
  });

  it('shows a package its new span – moved, scheduled or stretched – and "no time span" over the tray', () => {
    const range = { start: day('2026-10-12'), end: day('2026-10-18') };
    for (const mode of ['move', 'schedule', 'end'] as const) {
      expect(liveLine({ kind: 'package', workPackage, mode }, { kind: 'row', band: 'p1', workPackageId: 'w-repo' }, day('2026-10-12'), range, context()))
        .toEqual({ text: '12.–18.10.', refused: false });
    }
    // stretching needs no target at all
    expect(liveLine({ kind: 'package', workPackage, mode: 'start' }, null, null, range, context()).text).toBe('12.–18.10.');
    expect(liveLine({ kind: 'package', workPackage, mode: 'move' }, { kind: 'tray', band: 'p1' }, null, null, context()))
      .toEqual({ text: 'Ohne Zeitraum', refused: false, arrow: true });
    expect(liveLine({ kind: 'package', workPackage, mode: 'move' }, { kind: 'row', band: 'p2', workPackageId: null }, day('2026-10-12'), range, context()))
      .toEqual({ text: 'Nicht hier – nur in AutoPilot', refused: true });
  });

  it('shows a milestone its day wherever it hangs over the axis', () => {
    expect(liveLine({ kind: 'milestone', milestone }, { kind: 'milestones' }, day('2026-10-14'), null, context()))
      .toEqual({ text: 'Mi., 14.10.', refused: false });
    expect(liveLine({ kind: 'milestone', milestone }, { kind: 'row', band: 'p2', workPackageId: null }, day('2026-10-14'), null, context()).refused)
      .toBe(false);
    expect(liveLine({ kind: 'milestone', milestone }, { kind: 'tray', band: 'p1' }, null, null, context()).refused).toBe(true);
  });
});
