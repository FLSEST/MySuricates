import { describe, it, expect } from 'vitest';
import { NO_PROJECT, buildOutline, countTodos, moved, percentDone } from '../goalOutline';
import type { GoalMilestone, GoalProject, GoalTodoItem } from '../../../types';

function todo(id: string, overrides: Partial<GoalTodoItem> = {}): GoalTodoItem {
  return {
    id, title: `Todo ${id}`, status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
    goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null,
    openQuestion: null, updatedAt: '2026-09-23T00:00:00Z', dueDate: null, milestoneId: null, ...overrides,
  };
}

function milestone(id: string, overrides: Partial<GoalMilestone> = {}): GoalMilestone {
  return {
    id, goalId: 'g1', title: `Sub-goal ${id}`, dueDate: null, projectId: 'p1', position: 0, openQuestions: 0,
    state: 'OPEN', total: 0, done: 0, reachedAt: null, reopened: false,
    createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z', ...overrides,
  };
}

function project(projectId: string, name: string, position: number): GoalProject {
  return { projectId, name, archived: false, position, total: 0, done: 0, openQuestions: 0 };
}

const projects = [project('p2', 'Site', 1), project('p1', 'AutoPilot', 0)];

describe('buildOutline (c60bf914)', () => {
  it('orders projects by position and their sub-goals by position, TODOs in server order', () => {
    const outline = buildOutline({
      projects,
      milestones: [milestone('m2', { position: 1 }), milestone('m1', { position: 0 }), milestone('s1', { projectId: 'p2' })],
      todos: [
        todo('a', { milestoneId: 'm2' }), todo('b', { milestoneId: 'm1' }), todo('c', { milestoneId: 'm2' }),
        todo('d', { projectId: 'p2', projectName: 'Site' }),
      ],
    });

    expect(outline.projects.map((group) => group.key)).toEqual(['p1', 'p2']);
    const [autopilot, site] = outline.projects;
    expect(autopilot!.subgoals.map((s) => [s.milestone.id, s.todos.map((t) => t.todo.id)])).toEqual([['m1', ['b']], ['m2', ['a', 'c']]]);
    expect(site!.subgoals.map((s) => s.milestone.id)).toEqual(['s1']);
    expect(site!.loose.map((entry) => entry.todo.id)).toEqual(['d']);
    expect(outline.noProject).toBeNull();
  });

  it('puts TODOs without sub-goal under "no sub-goal" of their project and TODOs without project under "no project"', () => {
    const outline = buildOutline({
      projects,
      milestones: [],
      todos: [todo('a'), todo('b', { projectId: null, projectName: null }), todo('c', { milestoneId: 'gone' })],
    });
    expect(outline.projects[0]!.loose.map((entry) => entry.todo.id)).toEqual(['a', 'c']);
    expect(outline.noProject?.key).toBe(NO_PROJECT);
    expect(outline.noProject?.project).toBeNull();
    expect(outline.noProject?.loose.map((entry) => entry.todo.id)).toEqual(['b']);
  });

  it('counts done/total and open questions per node, without archived TODOs and without questions of done ones', () => {
    const outline = buildOutline({
      projects: [project('p1', 'AutoPilot', 0)],
      milestones: [milestone('m1')],
      todos: [
        todo('a', { milestoneId: 'm1', status: 'DONE', openQuestion: 'settled?' }),
        todo('b', { milestoneId: 'm1', openQuestion: 'Which look?' }),
        todo('c', { milestoneId: 'm1', status: 'ARCHIVED', openQuestion: 'old' }),
        todo('d', { status: 'IN_PROGRESS', openQuestion: 'Who decides?' }),
        todo('e', { status: 'DONE' }),
      ],
    });
    const [autopilot] = outline.projects;
    expect(autopilot!.subgoals[0]!.counts).toEqual({ total: 2, done: 1, openQuestions: 1 });
    expect(autopilot!.subgoals[0]!.todos.map((entry) => entry.todo.id)).toEqual(['a', 'b']);
    expect(autopilot!.counts).toEqual({ total: 4, done: 2, openQuestions: 2 });
    expect(percentDone(autopilot!.counts)).toBe(50);
    expect(percentDone(countTodos([]))).toBe(0);
  });

  it('marks old data: a TODO of another project in a sub-goal (⚠), but reads it', () => {
    const outline = buildOutline({
      projects,
      milestones: [milestone('m1')],
      todos: [todo('a', { milestoneId: 'm1' }), todo('b', { milestoneId: 'm1', projectId: 'p2', projectName: 'Site' })],
    });
    expect(outline.projects[0]!.subgoals[0]!.todos.map((entry) => [entry.todo.id, entry.mismatch])).toEqual([['a', false], ['b', true]]);
  });

  it('keeps a project that only comes in through a TODO or a sub-goal as its own, unlinked group', () => {
    const outline = buildOutline({
      projects: [project('p1', 'AutoPilot', 0)],
      milestones: [milestone('x1', { projectId: 'p9' })],
      todos: [todo('a', { projectId: 'p9', projectName: 'Elsewhere' }), todo('b', { projectId: 'p8', projectName: 'Docs' })],
    });
    expect(outline.projects.map((group) => [group.key, group.project?.name, group.linked])).toEqual([
      ['p1', 'AutoPilot', true], ['p9', 'Elsewhere', false], ['p8', 'Docs', false],
    ]);
    expect(outline.projects[1]!.subgoals.map((s) => s.milestone.id)).toEqual(['x1']);
  });

  it('shows a sub-goal whose project was deleted under "no project"', () => {
    const outline = buildOutline({ projects, milestones: [milestone('m1', { projectId: null })], todos: [] });
    expect(outline.noProject?.subgoals.map((s) => s.milestone.id)).toEqual(['m1']);
  });
});

describe('moved', () => {
  it('swaps with the neighbour and refuses at the edges', () => {
    expect(moved(['a', 'b', 'c'], 1, -1)).toEqual(['b', 'a', 'c']);
    expect(moved(['a', 'b', 'c'], 1, 1)).toEqual(['a', 'c', 'b']);
    expect(moved(['a', 'b'], 0, -1)).toBeNull();
    expect(moved(['a', 'b'], 1, 1)).toBeNull();
    expect(moved(['a'], -1, 1)).toBeNull();
  });
});
