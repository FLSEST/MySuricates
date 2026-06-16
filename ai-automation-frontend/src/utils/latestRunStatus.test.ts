import { describe, it, expect } from 'vitest';
import { latestRunStatusMap } from './latestRunStatus';
import type { WorkflowRun } from '../types';

function run(workflowId: string, status: WorkflowRun['status'], startedAt: string): WorkflowRun {
  return {
    id: `${workflowId}-${startedAt}`, workflowId, workflowName: workflowId,
    projectName: null, projectPath: null, status, steps: [],
    startedAt, completedAt: null, durationMs: null, userRating: null,
    error: null, metadata: {},
  } as WorkflowRun;
}

describe('latestRunStatusMap', () => {
  it('keeps the most recent run status per workflow', () => {
    const runs = [
      run('w1', 'FAILED', '2026-06-15T08:00:00Z'),
      run('w1', 'COMPLETED', '2026-06-15T10:00:00Z'),
      run('w2', 'RUNNING', '2026-06-15T09:00:00Z'),
    ];
    const m = latestRunStatusMap(runs);
    expect(m.get('w1')).toBe('COMPLETED');
    expect(m.get('w2')).toBe('RUNNING');
  });

  it('returns empty map for no runs', () => {
    expect(latestRunStatusMap([]).size).toBe(0);
  });
});
