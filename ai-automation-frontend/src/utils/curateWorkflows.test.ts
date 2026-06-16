import { describe, it, expect } from 'vitest';
import { curateWorkflows } from './curateWorkflows';
import type { Workflow } from '../types';

function wf(id: string, runCount: number, name = id): Workflow {
  return {
    id, projectId: 'p', projectName: 'P', name, description: '', icon: '⚡',
    blocks: [], activatedSkillIds: [], activatedSkillNames: [], version: '1',
    runCount, instructions: null, model: null, provider: null,
    createdAt: '', updatedAt: '', tags: [],
  } as Workflow;
}

describe('curateWorkflows', () => {
  const all = [wf('a', 5), wf('b', 100), wf('c', 50), wf('d', 0), wf('e', 200), wf('f', 10), wf('g', 1)];

  it('fills curated with top-by-runCount up to limit', () => {
    const { curated } = curateWorkflows(all, [], 3);
    expect(curated.map((w) => w.id)).toEqual(['e', 'b', 'c']);
  });

  it('always includes pinned, even beyond runCount ordering', () => {
    const { curated } = curateWorkflows(all, ['d'], 3);
    expect(curated.map((w) => w.id)).toEqual(['d', 'e', 'b']);
  });

  it('rest excludes everything curated', () => {
    const { curated, rest } = curateWorkflows(all, ['d'], 3);
    const curatedIds = new Set(curated.map((w) => w.id));
    expect(rest.every((w) => !curatedIds.has(w.id))).toBe(true);
    expect(curated.length + rest.length).toBe(all.length);
  });

  it('keeps all pinned even if pinned count exceeds limit', () => {
    const { curated } = curateWorkflows(all, ['a', 'f', 'g', 'd'], 2);
    expect(curated.map((w) => w.id).sort()).toEqual(['a', 'd', 'f', 'g']);
  });
});
