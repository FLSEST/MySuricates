import { describe, it, expect } from 'vitest';
import { applyDecision, applyDecisionToDetail, decisionCounts } from '../decision';
import type { GoalDetail, GoalTodoItem } from '../../../types';

const todo = (id: string, extra: Partial<GoalTodoItem> = {}): GoalTodoItem => ({
  id, title: id, status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [], goalRank: null,
  complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null, openQuestion: null,
  updatedAt: '', dueDate: null, workPackageId: 'w1', decision: null, decidedAt: null, decidedBy: null, decisionState: 'NONE', ...extra,
});

// 68a5e24e: „Entschieden“ als sichtbarer Zustand am TODO
describe('applyDecision', () => {
  it('closes the question with a decision, keeps who and when', () => {
    const asking = todo('a', { openQuestion: 'Which licence?', decisionState: 'QUESTION' });
    expect(applyDecision(asking, '  MIT ', '2026-09-28T10:00:00Z')).toMatchObject({
      openQuestion: null, decision: 'MIT', decidedAt: '2026-09-28T10:00:00Z', decidedBy: 'USER', decisionState: 'DECIDED',
    });
  });

  it('removes a decision with empty text – back to the question if one is open, else to nothing', () => {
    const decided = todo('a', { decision: 'MIT', decidedAt: 'x', decidedBy: 'AI', decisionState: 'DECIDED' });
    expect(applyDecision(decided, '', 'now')).toMatchObject({ decision: null, decidedAt: null, decidedBy: null, decisionState: 'NONE' });
    const askedAgain = { ...decided, openQuestion: 'And GPL?', decisionState: 'QUESTION' as const };
    expect(applyDecision(askedAgain, ' ', 'now').decisionState).toBe('QUESTION');
  });
});

describe('applyDecisionToDetail', () => {
  const detail = {
    goal: { progress: { total: 2, open: 2, inProgress: 0, backlog: 0, done: 0, openQuestions: 1, decided: 0, percentDone: 0 } },
    todos: [todo('a', { openQuestion: 'Which?', decisionState: 'QUESTION' }), todo('b')],
    projects: [{ projectId: 'p1', name: 'AutoPilot', archived: false, position: 0, total: 2, done: 0, openQuestions: 1, decided: 0 }],
    workPackages: [{ id: 'w1', openQuestions: 1, decided: 0 }],
  } as unknown as GoalDetail;

  it('moves the counters of goal, project and package from open to decided, and back on removal', () => {
    const decided = applyDecisionToDetail(detail, 'a', 'This', 'now');
    expect(decided.goal.progress).toMatchObject({ openQuestions: 0, decided: 1 });
    expect(decided.projects[0]).toMatchObject({ openQuestions: 0, decided: 1 });
    expect(decided.workPackages[0]).toMatchObject({ openQuestions: 0, decided: 1 });
    const removed = applyDecisionToDetail(decided, 'a', '', 'later');
    // the question was closed by the decision – removing it does not bring the question back
    expect(removed.goal.progress).toMatchObject({ openQuestions: 0, decided: 0 });
    expect(removed.todos[0]!.decisionState).toBe('NONE');
  });

  it('leaves the detail alone for an unknown TODO and counts no archived one', () => {
    expect(applyDecisionToDetail(detail, 'zz', 'x', 'now')).toBe(detail);
    const archived = { ...detail, todos: [todo('a', { status: 'ARCHIVED' })] };
    expect(applyDecisionToDetail(archived, 'a', 'x', 'now').goal.progress.decided).toBe(0);
  });
});

describe('decisionCounts', () => {
  it('counts open questions of unfinished TODOs and decisions of all but archived ones', () => {
    expect(decisionCounts([
      todo('a', { openQuestion: 'x', decisionState: 'QUESTION' }),
      todo('b', { openQuestion: 'y', status: 'DONE', decisionState: 'QUESTION' }),
      todo('c', { decisionState: 'DECIDED', decision: 'z', status: 'DONE' }),
      todo('d', { decisionState: 'DECIDED', decision: 'z', status: 'ARCHIVED' }),
    ])).toEqual({ openQuestions: 1, decided: 1 });
  });
});
