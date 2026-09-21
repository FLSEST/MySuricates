import { describe, it, expect } from 'vitest';
import { buildHomeTiles, greetingKey } from './homeTiles';
import type { Workflow } from '../types';

function wf(id: string, runCount: number): Workflow {
  return {
    id, projectId: 'p', projectName: 'P', name: id, description: '', icon: '⚡',
    blocks: [], activatedSkillIds: [], activatedSkillNames: [], version: '1',
    runCount, instructions: null, model: null, provider: null,
    createdAt: '', updatedAt: '', tags: [],
  } as Workflow;
}

describe('buildHomeTiles', () => {
  it('returns the nav tiles in fixed order', () => {
    const { navTiles } = buildHomeTiles([], [], 4);
    expect(navTiles.map((t) => t.id)).toEqual([
      'workflows', 'results', 'projects', 'schedules', 'todos', 'activity',
      'factory', 'integrations', 'reports', 'wishes',
    ]);
    expect(navTiles.map((t) => t.to)).toEqual([
      '/workflows', '/runs', '/projects', '/schedules', '/todos', '/activity',
      '/factory', '/integrations', '/reports', '/wishes',
    ]);
  });

  it('leaves out tiles of disabled modules (core preset)', () => {
    const { navTiles } = buildHomeTiles([], [], 4, new Set(['factory', 'integrations', 'audit', 'wishes']));
    expect(navTiles.map((t) => t.id)).toEqual([
      'workflows', 'results', 'projects', 'schedules', 'todos', 'activity',
    ]);
  });

  it('keeps a module tile while only other modules are disabled', () => {
    const { navTiles } = buildHomeTiles([], [], 4, new Set(['skills']));
    expect(navTiles).toHaveLength(10);
  });

  it('returns top-N run tiles by runCount', () => {
    const all = [wf('a', 5), wf('b', 100), wf('c', 50)];
    const { runTiles } = buildHomeTiles(all, [], 2);
    expect(runTiles.map((w) => w.id)).toEqual(['b', 'c']);
  });

  it('no workflows → empty run tiles but nav tiles intact', () => {
    const { navTiles, runTiles } = buildHomeTiles([], [], 4);
    expect(runTiles).toEqual([]);
    expect(navTiles.length).toBe(10);
  });

  it('surfaces pinned workflows among run tiles', () => {
    const all = [wf('a', 5), wf('b', 100), wf('c', 50)];
    const { runTiles } = buildHomeTiles(all, ['a'], 2);
    expect(runTiles.map((w) => w.id)).toContain('a');
  });
});

describe('greetingKey', () => {
  it('morning before 11', () => {
    expect(greetingKey(new Date(2026, 5, 16, 8, 0))).toBe('greetingMorning');
  });
  it('day between 11 and 17', () => {
    expect(greetingKey(new Date(2026, 5, 16, 14, 0))).toBe('greetingDay');
  });
  it('evening from 18', () => {
    expect(greetingKey(new Date(2026, 5, 16, 20, 0))).toBe('greetingEvening');
  });
});
