import { describe, it, expect } from 'vitest';
import { de } from '../i18n/de';
import { en } from '../i18n/en';
import { formatTimestamp } from './localeFormat';
import { buildFailedRunTodo } from './failedRunTodo';
import type { WorkflowRun } from '../types';

const run = {
  id: 'run-9', workflowId: 'wf-1', workflowName: 'Deploy (from run)', status: 'FAILED',
  startedAt: '2026-09-14T10:00:00Z',
  error: { message: 'exit 1' },
  steps: [
    { id: 's0', stepIndex: 0, status: 'COMPLETED', blockType: 'shell', error: null },
    { id: 's1', stepIndex: 1, status: 'FAILED', blockType: 'simple', error: 'model timed out' },
  ],
} as unknown as WorkflowRun;

describe('buildFailedRunTodo', () => {
  it('writes the TODO in German like before', () => {
    const todo = buildFailedRunTodo(run, { name: 'Deploy', projectId: 'p1' }, 'wf-1', de, 'de');
    expect(todo).toEqual({
      title: 'Deploy: Run fehlgeschlagen',
      description: [
        '**Workflow:** Deploy',
        `**Run:** [${formatTimestamp(run.startedAt, 'de')}](/workflows/wf-1/run/run-9)`,
        '**Fehlgeschlagener Step:** Step 2 (simple)',
        '**Fehler:** exit 1',
        '**Step-Fehler:** model timed out',
      ].join('\n'),
      priority: 'HIGH',
      tags: ['failed-run'],
      projectId: 'p1',
      source: 'WORKFLOW',
      workflowRunId: 'run-9',
    });
  });

  it('writes the TODO in English for an English app', () => {
    const todo = buildFailedRunTodo(run, { name: 'Deploy', projectId: 'p1' }, 'wf-1', en, 'en');
    expect(todo.title).toBe('Deploy: Run failed');
    expect(todo.description!.split('\n')).toEqual([
      '**Workflow:** Deploy',
      `**Run:** [${formatTimestamp(run.startedAt, 'en')}](/workflows/wf-1/run/run-9)`,
      '**Failed step:** Step 2 (simple)',
      '**Error:** exit 1',
      '**Step error:** model timed out',
    ]);
  });

  it('falls back to the name of the run and leaves out what is unknown', () => {
    const bare = { ...run, error: null, steps: [] } as unknown as WorkflowRun;
    const todo = buildFailedRunTodo(bare, undefined, 'wf-1', en, 'en');
    expect(todo.title).toBe('Deploy (from run): Run failed');
    expect(todo.description!.split('\n')).toHaveLength(2);
    expect(todo.projectId).toBeUndefined();
  });

  it('keeps names with replacement patterns as they are', () => {
    const todo = buildFailedRunTodo(run, { name: 'Price $& more', projectId: '' }, 'wf-1', en, 'en');
    expect(todo.title).toBe('Price $& more: Run failed');
    expect(todo.projectId).toBeUndefined();
  });
});
