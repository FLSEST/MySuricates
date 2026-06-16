import { describe, it, expect } from 'vitest';
import { matchWorkflows } from './matchWorkflows';
import type { Workflow } from '../types';

function wf(partial: Partial<Workflow>): Workflow {
  return {
    id: 'id', projectId: 'p', projectName: 'P', name: '', description: '',
    icon: '⚡', blocks: [], activatedSkillIds: [], activatedSkillNames: [],
    version: '1', runCount: 0, instructions: null, model: null, provider: null,
    createdAt: '', updatedAt: '', tags: [], ...partial,
  } as Workflow;
}

describe('matchWorkflows', () => {
  const workflows = [
    wf({ id: 'job', name: 'Job Shortlist Generator', description: 'finds jobs', tags: ['jobs'], runCount: 69 }),
    wf({ id: 'radar', name: 'Lebens-Radar', description: 'weekly life radar', tags: ['radar'], runCount: 51 }),
    wf({ id: 'lern', name: 'Lernpaket Generator', description: 'learning packages', tags: ['learning'], runCount: 227 }),
  ];

  it('returns [] for empty intent', () => {
    expect(matchWorkflows('   ', workflows)).toEqual([]);
  });

  it('matches by name token', () => {
    const r = matchWorkflows('job', workflows);
    expect(r[0].workflow.id).toBe('job');
  });

  it('matches by tag', () => {
    const r = matchWorkflows('radar', workflows);
    expect(r.map((m) => m.workflow.id)).toContain('radar');
  });

  it('filters out non-matches', () => {
    const r = matchWorkflows('xyzzy', workflows);
    expect(r).toEqual([]);
  });

  it('breaks ties by runCount (more-used first)', () => {
    const tie = [wf({ id: 'a', name: 'report alpha', runCount: 5 }), wf({ id: 'b', name: 'report beta', runCount: 50 })];
    const r = matchWorkflows('report', tie);
    expect(r[0].workflow.id).toBe('b');
  });
});
