import { describe, it, expect } from 'vitest';
import { parseHomeQuery } from './query';
import { matchActions, type ActionId } from './actions';
import { matchHints, type HintEntry } from './explanations';
import { arrangeGroups, flattenGroups, hasFoundHit } from './arrange';
import type { GroupId, HomeItem, HomeSource } from './types';

const parse = (q: string) => parseHomeQuery(q, 3);

const LABELS_DE: Record<ActionId, string> = {
  locale: 'Sprache auf English umschalten',
  theme: 'Zu hell wechseln',
  'create-workflow': 'Workflow aus Vorlage anlegen',
  'create-project': 'Projekt anlegen',
  settings: 'Einstellungen öffnen',
  hints: 'Hinweise wieder anzeigen',
  pro: 'Zum Pro-Modus wechseln',
};

describe('matchActions', () => {
  it.each([
    ['englisch', 'locale'],
    ['language', 'locale'],
    ['dark', 'theme'],
    ['hell', 'theme'],
    ['neues projekt', 'create-project'],
    ['vorlage', 'create-workflow'],
    ['settings', 'settings'],
    ['hinweise', 'hints'],
    ['pro', 'pro'],
  ])('%s → %s first', (q, id) => {
    expect(matchActions(parse(q), LABELS_DE)[0]).toBe(id);
  });

  it('also finds an action by its label', () => {
    expect(matchActions(parse('öffnen'), LABELS_DE)).toContain('settings');
  });

  it('is empty for unrelated words', () => {
    expect(matchActions(parse('backup'), LABELS_DE)).toEqual([]);
  });
});

describe('matchHints', () => {
  const hints: HintEntry[] = [
    { id: 'projects-directory', title: 'Ein Projekt ist ein Verzeichnis', points: ['Workflows laufen im Ordner'] },
    { id: 'run-failed', title: 'Ein fehlgeschlagener Lauf behält, was er getan hat', points: ['Der Schritt ist markiert'] },
    { id: 'wishes-loop', title: 'Ein Ziel beschreiben, Workflows bekommen', points: ['Projekt egal'] },
  ];

  it('answers "was ist ein Projekt" with the project hint first', () => {
    expect(matchHints(parse('Was ist ein Projekt?'), hints, new Set())).toEqual(['projects-directory', 'wishes-loop']);
  });

  it('hides hints of disabled modules', () => {
    expect(matchHints(parse('Projekt'), hints, new Set(['wishes']))).toEqual(['projects-directory']);
  });

  it('matches inflected words and ignores short ones', () => {
    expect(matchHints(parse('fehlgeschlagen'), hints, new Set())).toEqual(['run-failed']);
    expect(matchHints(parse('er'), hints, new Set())).toEqual([]);
  });
});

const item = (id: string, fallback = false): HomeItem =>
  ({ id, icon: 'workflow', label: id, primary: { label: 'x', action: { kind: 'navigate', to: '/' } }, fallback });
const source = (id: GroupId, n: number, extra: Partial<HomeSource> = {}): HomeSource =>
  ({ id, items: Array.from({ length: n }, (_, i) => item(`${id}-${i}`)), loading: false, ...extra });

describe('arrangeGroups', () => {
  it('puts workflows first without an intent and drops empty groups', () => {
    const groups = arrangeGroups([source('actions', 1), source('todos', 0), source('workflows', 2)], []);
    expect(groups.map((g) => g.id)).toEqual(['workflows', 'actions']);
  });

  it('lifts the groups named by intents, in typed order', () => {
    const groups = arrangeGroups(
      [source('workflows', 1), source('runs', 1), source('schedules', 1)], parse('backup failed schedule').intents,
    );
    expect(groups.map((g) => g.id)).toEqual(['runs', 'schedules', 'workflows']);
  });

  it('caps each group and the total, and says when hits were left out', () => {
    const groups = arrangeGroups(
      [source('workflows', 5), source('runs', 5), source('templates', 5), source('todos', 5), source('projects', 5)], [],
    );
    expect(groups.map((g) => g.items.length)).toEqual([3, 3, 3, 3]);
    expect(flattenGroups(groups)).toHaveLength(12);
    expect(groups[0]!.more).toBe(true);
  });

  it('does not count the generated row as a hidden hit, but passes on a source\'s "more"', () => {
    const todos: HomeSource = { id: 'todos', items: [item('t1'), item('create', true)], loading: false, more: false };
    expect(arrangeGroups([todos], [])[0]!.more).toBe(false);
    expect(arrangeGroups([{ ...todos, more: true }], [])[0]!.more).toBe(true);
  });

  it('tells found hits from the generated row', () => {
    expect(hasFoundHit(arrangeGroups([{ id: 'todos', items: [item('c', true)], loading: false }], []))).toBe(false);
    expect(hasFoundHit(arrangeGroups([source('workflows', 1)], []))).toBe(true);
  });
});
