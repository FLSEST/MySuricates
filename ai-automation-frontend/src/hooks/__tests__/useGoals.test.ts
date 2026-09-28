import { describe, it, expect } from 'vitest';
import { goalRelationsRetry, normalizeGoalDetail } from '../useGoals';

describe('goalRelationsRetry', () => {
  it('never retries a 404 – an older backend without the relations endpoint stays that way until restart', () => {
    expect(goalRelationsRetry(0, { status: 404, error: 'Not Found' })).toBe(false);
  });

  it('retries other errors like the app default (once), but never a 429', () => {
    expect(goalRelationsRetry(0, { status: 500, error: 'boom' })).toBe(true);
    expect(goalRelationsRetry(1, { status: 500, error: 'boom' })).toBe(false);
    expect(goalRelationsRetry(0, { status: 429, error: 'slow down' })).toBe(false);
  });
});

// c60bf914: the frontend goes live with the merge, the backend only after a restart – both answers must work
describe('normalizeGoalDetail', () => {
  const todo = (id: string, extra: Record<string, unknown> = {}) => ({
    id, title: id, status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [], goalRank: null,
    complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null, openQuestion: null,
    updatedAt: '2026-09-23T00:00:00Z', dueDate: null, ...extra,
  });
  const goal = {
    id: 'g1', title: 'Release', description: null, status: 'ACTIVE', targetDate: null, createdAt: '', updatedAt: '',
    progress: { total: 3, open: 3, inProgress: 0, backlog: 0, done: 0, openQuestions: 1, percentDone: 0 },
    guard: { enabled: false, workflowId: null, scheduleId: null, nextRunAt: null },
  };
  /** Was ein Backend vor c60bf914 „milestone“ nannte, ist ein Arbeitspaket mit nur einer Fälligkeit. */
  const oldPackage = {
    id: 'm1', goalId: 'g1', title: 'Promo', dueDate: '2026-12-31T22:59:59Z', state: 'OPEN', total: 3, done: 0,
    reachedAt: '2026-09-01T00:00:00Z', reopened: false, createdAt: '', updatedAt: '',
  };

  it('reads an older backend: its milestones are work packages of one day, the TODOs point at them by milestoneId', () => {
    const older = {
      goal,
      todos: [
        todo('a', { milestoneId: 'm1', projectId: 'p2', projectName: 'Site', openQuestion: 'Which?' }),
        todo('b', { milestoneId: 'm1', projectId: 'p2', projectName: 'Site' }),
        todo('c', { milestoneId: 'm1' }),
        todo('d', { projectId: null, projectName: null }),
      ],
      byProject: [
        { projectId: 'p2', projectName: 'Site', total: 2, done: 0 },
        { projectId: 'p1', projectName: 'AutoPilot', total: 1, done: 0 },
        { projectId: null, projectName: null, total: 1, done: 0 },
      ],
      milestones: [oldPackage],
    };
    const detail = normalizeGoalDetail(older as never);

    expect(detail.legacyBackend).toBe(true);
    expect(detail.milestones).toEqual([]);
    expect(detail.projects).toEqual([
      { projectId: 'p2', name: 'Site', archived: false, position: 0, total: 2, done: 0, openQuestions: 1, decided: 0 },
      { projectId: 'p1', name: 'AutoPilot', archived: false, position: 1, total: 1, done: 0, openQuestions: 0, decided: 0 },
    ]);
    expect(detail.workPackages).toEqual([{
      id: 'm1', goalId: 'g1', title: 'Promo', projectId: 'p2', startDate: oldPackage.dueDate, endDate: oldPackage.dueDate,
      position: 0, state: 'OPEN', total: 3, done: 0, openQuestions: 1, decided: 0, completedAt: '2026-09-01T00:00:00Z', reopened: false,
      createdAt: '', updatedAt: '',
    }]);
    expect(detail.todos.map((item) => item.workPackageId)).toEqual(['m1', 'm1', 'm1', null]);
    expect(detail.todos[0]).not.toHaveProperty('milestoneId');
    // 68a5e24e: an older backend knows no decisions – a question is QUESTION, nothing is decided, no deciding
    expect(detail.todos.map((item) => item.decisionState)).toEqual(['QUESTION', 'NONE', 'NONE', 'NONE']);
    expect(detail.todos[0]).toMatchObject({ decision: null, decidedAt: null, decidedBy: null });
    expect(detail.goal.progress.decided).toBe(0);
    expect(detail.decisionsSupported).toBe(false);
  });

  it('takes the only project of the goal for an old package without TODOs, and copes without milestones and due dates', () => {
    const older = {
      goal,
      todos: [{ ...todo('a'), dueDate: undefined }],
      byProject: [{ projectId: 'p1', projectName: 'AutoPilot', total: 1, done: 0 }],
      milestones: [{ ...oldPackage, total: 0, dueDate: undefined }],
    };
    const detail = normalizeGoalDetail(older as never);
    expect(detail.workPackages[0]).toMatchObject({ projectId: 'p1', startDate: null, endDate: null });
    expect(detail.todos[0]).toMatchObject({ dueDate: null, workPackageId: null });

    const { milestones: _m, ...withoutMilestones } = older;
    const bare = normalizeGoalDetail(withoutMilestones as never);
    expect(bare.workPackages).toEqual([]);
    expect(bare.milestones).toEqual([]);
    expect(bare.legacyBackend).toBe(true);
  });

  it('keeps the answer of a current backend: work packages with their span, milestones with their links', () => {
    const current = {
      goal: { ...goal, progress: { ...goal.progress, decided: 1 } },
      todos: [todo('a', {
        workPackageId: 'w1', decision: 'MIT', decidedAt: '2026-09-27T10:00:00Z', decidedBy: 'USER', decisionState: 'DECIDED',
      })],
      byProject: [{ projectId: 'p1', projectName: 'AutoPilot', total: 1, done: 0 }],
      projects: [{ projectId: 'p1', name: 'AutoPilot', archived: true, position: 0, total: 1, done: 0, openQuestions: 4, decided: 3 }],
      workPackages: [{
        id: 'w1', goalId: 'g1', projectId: null, title: 'Repo sauber', startDate: '2026-10-11T22:00:00Z', endDate: '2026-10-18T21:59:59Z',
        position: 7, state: 'OPEN', total: 1, done: 0, openQuestions: 2, decided: 1, completedAt: null, reopened: false, createdAt: '', updatedAt: '',
      }],
      milestones: [{
        id: 'ms1', goalId: 'g1', title: 'Code-Freeze', dueDate: '2026-10-15T21:59:59Z', state: 'DUE_SOON', workPackageIds: ['w1'],
        packagesTotal: 1, packagesDone: 0, reachedAt: null, reopened: false, createdAt: '', updatedAt: '',
      }],
    };
    const detail = normalizeGoalDetail(current as never);
    expect(detail.legacyBackend).toBe(false);
    expect(detail.projects).toEqual(current.projects);
    // null stays null – a package whose project was deleted is not re-guessed
    expect(detail.workPackages).toEqual(current.workPackages);
    expect(detail.milestones).toEqual(current.milestones);
    expect(detail.todos[0]!.workPackageId).toBe('w1');
    // 68a5e24e: decisions and their counters pass through unchanged
    expect(detail.todos[0]).toMatchObject({ decision: 'MIT', decidedAt: '2026-09-27T10:00:00Z', decidedBy: 'USER', decisionState: 'DECIDED' });
    expect(detail.goal.progress.decided).toBe(1);
    expect(detail.decisionsSupported).toBe(true);
  });

  it('fills in what a current answer may leave out: empty links and counts of a milestone', () => {
    const detail = normalizeGoalDetail({
      goal, todos: [], byProject: [], projects: [], workPackages: [],
      milestones: [{ id: 'ms1', goalId: 'g1', title: 'Launch', dueDate: '2026-10-31T22:59:59Z', state: 'OPEN', createdAt: '', updatedAt: '' }],
    } as never);
    expect(detail.milestones[0]).toMatchObject({ workPackageIds: [], packagesTotal: 0, packagesDone: 0, reachedAt: null, reopened: false });
  });
});
