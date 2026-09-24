import { describe, it, expect } from 'vitest';
import { parseHomeQuery } from './query';
import {
  latestResultRuns, matchCatalog, matchProjects, pickApprovals, pickRuns, pickSchedules, scoreFields,
} from './match';
import type { PendingInput } from '../../hooks/usePendingInputs';
import type { PoolWorkflowEntry, Project, ScheduledWorkflow, WorkflowRunListItem, WorkflowTemplate } from '../../types';

// Wednesday, 2026-09-23, 10:00 local time
const NOW = new Date(2026, 8, 23, 10, 0, 0);
const parse = (q: string) => parseHomeQuery(q, NOW.getDay());

const run = (id: string, workflowId: string, workflowName: string, status: string, startedAt: string, error?: string) =>
  ({ id, workflowId, workflowName, status, startedAt, error: error ? { message: error } : null }) as unknown as WorkflowRunListItem;

const template = (id: string, name: string, description = '', kind: 'TEMPLATE' | 'BLUEPRINT' = 'TEMPLATE') =>
  ({ id, name: { de: name, en: name }, description: { de: description, en: description }, icon: '', category: null, kind }) as unknown as WorkflowTemplate;

const poolEntry = (id: string, name: string, tags: string[] = []) =>
  ({ id, name: { en: name }, description: { en: '' }, icon: '', category: null, tags }) as unknown as PoolWorkflowEntry;

const schedule = (id: string, workflowName: string, cron: string, extra: Partial<ScheduledWorkflow> = {}) =>
  ({ id, workflowId: `w-${id}`, workflowName, chainName: null, scheduleType: 'CRON', cronExpression: cron,
    enabled: true, nextRunAt: null, description: '', ...extra }) as unknown as ScheduledWorkflow;

const pending = (runId: string, workflowName: string, question: string): PendingInput => ({
  runId, workflowId: 'w1', workflowName, type: 'approval', blockId: 'b1', question, options: ['Yes', 'No'],
  rejectOptions: ['No'], stepIndex: 0, requestedAt: '2026-09-23T08:00:00Z', timeoutAt: null,
});

describe('scoreFields', () => {
  it('weighs hits per field', () => {
    expect(scoreFields(['backup'], [['Backup photos', 3], ['daily backup', 1]])).toBe(4);
  });
});

describe('matchCatalog', () => {
  const templates = [template('t1', 'Daily news digest', 'Summarize news'), template('bp', 'News blueprint', '', 'BLUEPRINT')];
  const pool = [poolEntry('p1', 'Photo backup', ['photos']), poolEntry('p2', 'Newsletter')];

  it('finds templates and pool entries, name hits first, never blueprints', () => {
    const hits = matchCatalog(['news'], templates, pool, 'en');
    expect(hits.map((h) => `${h.source}:${h.entry.id}`)).toEqual(['template:t1', 'pool:p2']);
  });

  it('searches pool tags', () => {
    expect(matchCatalog(['photos'], templates, pool, 'en').map((h) => h.entry.id)).toEqual(['p1']);
  });

  it('is empty without tokens', () => {
    expect(matchCatalog([], templates, pool, 'en')).toEqual([]);
  });
});

describe('matchProjects', () => {
  it('matches by name and description', () => {
    const projects = [
      { id: 'a', name: 'Haushalt', description: 'Rechnungen und Belege' },
      { id: 'b', name: 'Blog' },
    ] as Project[];
    expect(matchProjects(['rechnung'], projects).map((p) => p.id)).toEqual(['a']);
    expect(matchProjects(['blog'], projects).map((p) => p.id)).toEqual(['b']);
  });
});

describe('latestResultRuns', () => {
  it('keeps the newest finished run per workflow', () => {
    const map = latestResultRuns([
      run('r1', 'w1', 'A', 'COMPLETED', '2026-09-20T10:00:00Z'),
      run('r2', 'w1', 'A', 'COMPLETED_WITH_ERRORS', '2026-09-22T10:00:00Z'),
      run('r3', 'w1', 'A', 'FAILED', '2026-09-23T09:00:00Z'),
    ]);
    expect(map.get('w1')?.id).toBe('r2');
  });
});

describe('pickRuns', () => {
  const runs = [
    run('r1', 'w1', 'Backup photos', 'FAILED', '2026-09-23T07:00:00', 'disk full'),
    run('r2', 'w2', 'Morning digest', 'FAILED', '2026-09-21T07:00:00'),
    run('r3', 'w1', 'Backup photos', 'COMPLETED', '2026-09-22T07:00:00'),
    run('r4', 'w2', 'Morning digest', 'COMPLETED', '2026-09-23T06:00:00'),
  ];

  it('lists failed runs on a failure intent, newest first', () => {
    expect(pickRuns(parse('fehlgeschlagen'), runs, NOW).map((r) => r.id)).toEqual(['r1', 'r2']);
  });

  it('narrows failures by content words and by "today"', () => {
    expect(pickRuns(parse('digest failed'), runs, NOW).map((r) => r.id)).toEqual(['r2']);
    expect(pickRuns(parse('disk error'), runs, NOW).map((r) => r.id)).toEqual(['r1']);
    expect(pickRuns(parse('was ist heute schiefgelaufen'), runs, NOW).map((r) => r.id)).toEqual(['r1']);
  });

  it('otherwise shows the newest run per matching workflow', () => {
    expect(pickRuns(parse('backup'), runs, NOW).map((r) => r.id)).toEqual(['r1']);
    expect(pickRuns(parse('xyz'), runs, NOW)).toEqual([]);
  });
});

describe('pickApprovals', () => {
  const list = [pending('r1', 'Invoice check', 'Pay **42 €**?'), pending('r2', 'Blog post', 'Publish now?')];

  it('shows all on a waiting intent (de + en)', () => {
    expect(pickApprovals(parse('was wartet'), list)).toHaveLength(2);
    expect(pickApprovals(parse('approvals'), list)).toHaveLength(2);
  });

  it('otherwise matches workflow name and question', () => {
    expect(pickApprovals(parse('publish'), list).map((p) => p.runId)).toEqual(['r2']);
    expect(pickApprovals(parse('invoice'), list).map((p) => p.runId)).toEqual(['r1']);
    expect(pickApprovals(parse('nothing'), list)).toEqual([]);
  });
});

describe('pickSchedules', () => {
  const list = [
    schedule('daily', 'Morning digest', '0 0 7 * * *', { nextRunAt: '2026-09-24T07:00:00' }),
    schedule('fri', 'Backup photos', '0 30 22 * * FRI', { nextRunAt: '2026-09-25T22:30:00' }),
    schedule('off', 'Old job', '0 0 7 * * *', { enabled: false }),
    schedule('once', 'Reminder', '', { scheduleType: 'ONCE', nextRunAt: '2026-09-24T12:00:00' }),
  ];

  it('keeps what fires on the named day', () => {
    expect(pickSchedules(parse('heute'), list, NOW).map((s) => s.id)).toEqual(['daily']);
    expect(pickSchedules(parse('morgen'), list, NOW).map((s) => s.id)).toEqual(['daily', 'once']);
    expect(pickSchedules(parse('friday'), list, NOW).map((s) => s.id)).toEqual(['daily', 'fri']);
  });

  it('lists all enabled ones on a schedule word, narrowed by content words', () => {
    expect(pickSchedules(parse('zeitplan'), list, NOW).map((s) => s.id)).toEqual(['daily', 'once', 'fri']);
    expect(pickSchedules(parse('wann läuft backup'), list, NOW).map((s) => s.id)).toEqual(['fri']);
  });

  it('matches by name without an intent', () => {
    expect(pickSchedules(parse('digest'), list, NOW).map((s) => s.id)).toEqual(['daily']);
    expect(pickSchedules(parse('nope'), list, NOW)).toEqual([]);
  });
});
