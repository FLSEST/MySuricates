import { describe, it, expect } from 'vitest';
import { COLUMNS, highlightFor, layoutRelations, resultTone, type MapNode } from '../goalRelationLayout';
import type { GoalTodoRelation } from '../../../types';

function relation(id: string, overrides: Partial<GoalTodoRelation> = {}): GoalTodoRelation {
  return {
    id, title: `Todo ${id}`, status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
    goalRank: null, candidate: false,
    origin: { kind: 'USER', workflowId: null, workflowName: null, runId: null, runStatus: null, findingIds: [], agent: null },
    workers: [], result: null, ...overrides,
  };
}

const fromGate = (id: string, runStatus: 'FAILED' | 'COMPLETED' = 'COMPLETED') => ({
  origin: { kind: 'WORKFLOW_RUN' as const, workflowId: 'wf-gate', workflowName: 'Quality Gate', runId: `run-${id}`, runStatus, findingIds: [], agent: null },
});
const fixer = { kind: 'TRIGGER' as const, workflowId: 'wf-fix', workflowName: 'Fixer', triggerId: 'tr', triggerLabel: 'Release-Fixer' };

const byKind = (nodes: MapNode[], kind: MapNode['kind']) => nodes.filter((n) => n.kind === kind);
const center = (n: MapNode) => n.y + n.h / 2;

describe('layoutRelations', () => {
  it('puts origin, todo, worker and result into their four columns', () => {
    const layout = layoutRelations([relation('a', {
      ...fromGate('a'), workers: [fixer],
      result: { runId: 'r1', workflowId: 'wf-fix', runStatus: 'COMPLETED', at: null, fixAttempts: 1, resolutionState: 'SUCCEEDED' },
    })]);

    for (const [kind, column] of [['origin', 0], ['todo', 1], ['worker', 2], ['result', 3]] as const) {
      const [node] = byKind(layout.nodes, kind);
      expect(node?.column).toBe(column);
      expect(node?.x).toBe(COLUMNS[column].x);
    }
    expect(layout.edges.map((e) => `${e.from} -> ${e.to}`)).toEqual([
      'origin:wf:wf-gate -> todo:a', 'todo:a -> worker:wf-fix', 'worker:wf-fix -> result:a',
    ]);
    expect(byKind(layout.nodes, 'result')[0]).toMatchObject({ tone: 'success', target: { type: 'run', id: 'r1' } });
  });

  it('merges the same workflow into one node, centred on its todos (fan-in)', () => {
    const layout = layoutRelations([
      relation('a', { ...fromGate('a'), workers: [fixer] }),
      relation('b', { ...fromGate('b', 'FAILED'), workers: [fixer, { ...fixer, kind: 'LINKED', triggerId: null, triggerLabel: null }] }),
      relation('c'),
    ]);

    const origins = byKind(layout.nodes, 'origin');
    expect(origins.map((n) => n.id).sort()).toEqual(['origin:user', 'origin:wf:wf-gate']);
    const gate = origins.find((n) => n.id === 'origin:wf:wf-gate')!;
    expect(gate.todoIds).toEqual(['a', 'b']);
    expect(gate.runStatus).toBe('FAILED');
    const todoA = layout.nodes.find((n) => n.id === 'todo:a')!;
    const todoB = layout.nodes.find((n) => n.id === 'todo:b')!;
    expect(center(gate)).toBeCloseTo((center(todoA) + center(todoB)) / 2);

    const [worker] = byKind(layout.nodes, 'worker');
    expect(worker).toMatchObject({ todoIds: ['a', 'b'], workerKinds: ['TRIGGER', 'LINKED'], triggerLabels: ['Release-Fixer'] });
    expect(layout.edges.filter((e) => e.to === 'worker:wf-fix')).toHaveLength(2);
  });

  it('shows a dotted stub where no workflow docks on, and says whether any todo has one', () => {
    const layout = layoutRelations([relation('a'), relation('b')]);

    const gaps = byKind(layout.nodes, 'gap');
    expect(gaps.map((g) => g.id)).toEqual(['gap:a', 'gap:b']);
    expect(layout.edges.filter((e) => e.style === 'dotted').map((e) => e.to)).toEqual(['gap:a', 'gap:b']);
    expect(layout.hasWorkers).toBe(false);
    expect(layoutRelations([relation('a', { workers: [fixer] })]).hasWorkers).toBe(true);
  });

  it('draws a result without a current worker straight from the todo, without a stub', () => {
    const layout = layoutRelations([relation('a', {
      result: { runId: 'r', workflowId: 'wf-gone', runStatus: 'FAILED', at: null, fixAttempts: 3, resolutionState: 'FAILED' },
    })]);

    expect(byKind(layout.nodes, 'gap')).toHaveLength(0);
    expect(layout.edges.map((e) => `${e.from} -> ${e.to}`)).toContain('todo:a -> result:a');
    expect(byKind(layout.nodes, 'result')[0]?.tone).toBe('danger');
  });

  it('groups the rows by project in work order, candidates last and dashed', () => {
    const layout = layoutRelations([
      relation('a', { projectId: 'p1', projectName: 'AutoPilot' }),
      relation('b', { projectId: 'p2', projectName: 'Site' }),
      relation('c', { projectId: 'p1', projectName: 'AutoPilot' }),
      relation('d', { projectId: null, projectName: null }),
      relation('x', { candidate: true, workers: [fixer] }),
    ]);

    expect(layout.groups.map((g) => [g.label, g.candidate])).toEqual([
      ['AutoPilot', false], ['Site', false], [null, false], [null, true],
    ]);
    const rows = byKind(layout.nodes, 'todo').sort((p, q) => p.y - q.y).map((n) => n.id);
    expect(rows).toEqual(['todo:a', 'todo:c', 'todo:b', 'todo:d', 'todo:x']);
    const candidate = layout.nodes.find((n) => n.id === 'todo:x')!;
    expect(candidate).toMatchObject({ candidate: true, tone: 'muted' });
    expect(layout.edges.filter((e) => e.todoId === 'x').every((e) => e.style === 'dashed')).toBe(true);
    expect(layout.hasWorkers).toBe(false);
  });

  it('lays out a goal without any todo as an empty map (no fixed rows, finite size)', () => {
    const layout = layoutRelations([]);
    expect(layout.nodes).toEqual([]);
    expect(layout.edges).toEqual([]);
    expect(layout.groups).toEqual([]);
    expect(layout.hasWorkers).toBe(false);
    expect(Number.isFinite(layout.height)).toBe(true);
    expect(layout.height).toBeGreaterThan(0);
  });

  it('lays out a goal with only candidates as the dashed group alone', () => {
    const layout = layoutRelations([
      relation('c1', { candidate: true, ...fromGate('c1'), workers: [fixer] }),
      relation('c2', { candidate: true }),
    ]);

    expect(layout.groups).toEqual([{ label: null, y: expect.any(Number), candidate: true }]);
    expect(byKind(layout.nodes, 'todo').map((n) => n.candidate)).toEqual([true, true]);
    expect(layout.edges.length).toBeGreaterThan(0);
    expect(layout.edges.filter((e) => e.style !== 'dotted').every((e) => e.style === 'dashed')).toBe(true);
    expect(layout.hasWorkers).toBe(false);
    for (const n of layout.nodes) expect(Number.isFinite(n.y)).toBe(true);
    expect(Number.isFinite(layout.height)).toBe(true);
  });

  it('keeps merged nodes of one column from overlapping', () => {
    const todos = Array.from({ length: 6 }, (_, i) => relation(`t${i}`, {
      origin: { kind: 'AGENT', workflowId: null, workflowName: null, runId: null, runStatus: null, findingIds: [], agent: `agent-${i}` },
    }));
    const origins = byKind(layoutRelations(todos).nodes, 'origin').sort((a, b) => a.y - b.y);
    for (let i = 1; i < origins.length; i++) {
      expect(origins[i]!.y).toBeGreaterThanOrEqual(origins[i - 1]!.y + origins[i - 1]!.h);
    }
  });

  it('links an audit origin to its finding when there is exactly one, else to the audit center', () => {
    const audit = (id: string, finding: string) => relation(id, {
      origin: { kind: 'AUDIT', workflowId: 'wf-audit', workflowName: 'Security', runId: null, runStatus: null, findingIds: [finding], agent: null },
    });
    expect(byKind(layoutRelations([audit('a', 'f1')]).nodes, 'origin')[0]?.target).toEqual({ type: 'finding', id: 'f1' });
    expect(byKind(layoutRelations([audit('a', 'f1'), audit('b', 'f2')]).nodes, 'origin')[0]?.target).toEqual({ type: 'audit' });
  });

  it('highlights every todo whose path runs through the hovered node', () => {
    const layout = layoutRelations([relation('a', { workers: [fixer] }), relation('b', { workers: [fixer] }), relation('c')]);
    expect([...highlightFor(layout, 'worker:wf-fix')!]).toEqual(['a', 'b']);
    expect([...highlightFor(layout, 'todo:c')!]).toEqual(['c']);
    expect(highlightFor(layout, null)).toBeNull();
  });
});

describe('resultTone', () => {
  it('maps run states to status colours, and falls back to the resolution state', () => {
    expect(resultTone('COMPLETED', null)).toBe('success');
    expect(resultTone('RUNNING', null)).toBe('info');
    expect(resultTone('CANCELLED', null)).toBe('danger');
    expect(resultTone(null, 'SUCCEEDED')).toBe('success');
    expect(resultTone(null, null)).toBe('neutral');
  });
});
