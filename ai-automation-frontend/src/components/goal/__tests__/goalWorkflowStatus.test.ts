import { describe, it, expect } from 'vitest';
import { ago, lastRunAt, lightOf, runTone, shownTone } from '../goalWorkflowStatus';
import type { WorkflowWidgetEntry } from '../../dashboard/workflowWidgetContract';

const entry = (status: string | null, light: 'GREEN' | 'YELLOW' | 'RED' | null = null, gate: string | null = null): WorkflowWidgetEntry => ({
  workflowId: 'w', workflowName: 'W', available: true,
  run: status ? { runId: 'r', status, startedAt: '2026-09-28T08:00:00Z', completedAt: status === 'RUNNING' ? null : '2026-09-28T08:05:00Z', durationMs: 1, gateStatus: gate } : null,
  resultState: light ? 'VALID' : 'MISSING',
  result: light ? { status: light, kpis: [], items: [] } : null,
} as WorkflowWidgetEntry);

// 52bc1e8c: what the dot of a workflow on the goal says
describe('goalWorkflowStatus', () => {
  it('colours the last run, neutral without one', () => {
    expect(runTone(entry('COMPLETED'))).toBe('success');
    expect(runTone(entry('FAILED'))).toBe('danger');
    expect(runTone(entry('RUNNING'))).toBe('info');
    expect(runTone(entry('CANCELLED'))).toBe('neutral');
    expect(runTone(entry(null))).toBe('neutral');
    expect(runTone(undefined)).toBe('neutral');
  });

  it('shows the light of a status workflow – unless its last run is running or failed', () => {
    expect(lightOf(entry('COMPLETED', 'YELLOW'))).toBe('YELLOW');
    expect(lightOf(entry('COMPLETED', null, 'RED'))).toBe('RED');
    expect(shownTone(entry('COMPLETED', 'RED'), true)).toBe('danger');
    expect(shownTone(entry('COMPLETED', 'YELLOW'), true)).toBe('warning');
    expect(shownTone(entry('COMPLETED', 'YELLOW'), false)).toBe('success');
    expect(shownTone(entry('RUNNING', 'GREEN'), true)).toBe('info');
    expect(shownTone(entry('FAILED', 'GREEN'), true)).toBe('danger');
  });

  it('tells when it ran, in the language of the interface', () => {
    expect(lastRunAt(entry('COMPLETED'))).toBe('2026-09-28T08:05:00Z');
    expect(lastRunAt(entry('RUNNING'))).toBe('2026-09-28T08:00:00Z');
    const now = new Date('2026-09-28T11:05:00Z');
    expect(ago('2026-09-28T08:05:00Z', now, 'en')).toBe('3 hours ago');
    expect(ago('2026-09-28T11:02:00Z', now, 'de')).toBe('vor 3 Minuten');
    expect(ago('2026-09-26T11:05:00Z', now, 'en')).toBe('2 days ago');
  });
});
