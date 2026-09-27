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
    updatedAt: '2026-09-23T00:00:00Z', dueDate: null, milestoneId: null, ...extra,
  });
  const goal = {
    id: 'g1', title: 'Release', description: null, status: 'ACTIVE', targetDate: null, createdAt: '', updatedAt: '',
    progress: { total: 3, open: 3, inProgress: 0, backlog: 0, done: 0, openQuestions: 1, percentDone: 0 },
    guard: { enabled: false, workflowId: null, scheduleId: null, nextRunAt: null },
  };
  const oldMilestone = {
    id: 'm1', goalId: 'g1', title: 'Promo', dueDate: '2026-12-31T22:59:59Z', state: 'OPEN', total: 3, done: 0,
    reachedAt: null, reopened: false, createdAt: '', updatedAt: '',
  };

  it('derives the projects of an older backend from byProject and places its sub-goals in the busiest project', () => {
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
      milestones: [oldMilestone],
    };
    const detail = normalizeGoalDetail(older as never);

    expect(detail.projects).toEqual([
      { projectId: 'p2', name: 'Site', archived: false, position: 0, total: 2, done: 0, openQuestions: 1 },
      { projectId: 'p1', name: 'AutoPilot', archived: false, position: 1, total: 1, done: 0, openQuestions: 0 },
    ]);
    expect(detail.milestones[0]).toMatchObject({ id: 'm1', projectId: 'p2', position: 0, openQuestions: 1, dueDate: oldMilestone.dueDate });
  });

  it('takes the only project of the goal for a sub-goal without TODOs, and copes without milestones and due dates', () => {
    const older = {
      goal,
      todos: [{ ...todo('a'), dueDate: undefined, milestoneId: undefined }],
      byProject: [{ projectId: 'p1', projectName: 'AutoPilot', total: 1, done: 0 }],
      milestones: [{ ...oldMilestone, total: 0 }],
    };
    const detail = normalizeGoalDetail(older as never);
    expect(detail.milestones[0]!.projectId).toBe('p1');
    expect(detail.todos[0]).toMatchObject({ dueDate: null, milestoneId: null });

    const { milestones: _m, ...withoutMilestones } = older;
    expect(normalizeGoalDetail(withoutMilestones as never).milestones).toEqual([]);
  });

  it('keeps the answer of a current backend as it is: projects, null dates, positions and question counts', () => {
    const current = {
      goal,
      todos: [todo('a', { milestoneId: 'm1' })],
      byProject: [{ projectId: 'p1', projectName: 'AutoPilot', total: 1, done: 0 }],
      projects: [{ projectId: 'p1', name: 'AutoPilot', archived: true, position: 0, total: 1, done: 0, openQuestions: 4 }],
      milestones: [{ ...oldMilestone, dueDate: null, projectId: null, position: 7, openQuestions: 2 }],
    };
    const detail = normalizeGoalDetail(current as never);
    expect(detail.projects).toEqual(current.projects);
    // null stays null – a sub-goal whose project was deleted is not re-guessed
    expect(detail.milestones[0]).toMatchObject({ dueDate: null, projectId: null, position: 7, openQuestions: 2 });
  });
});
