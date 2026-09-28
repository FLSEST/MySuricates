import { describe, it, expect } from 'vitest';
import { forBackend, formOf, packageOptions, planningChanges, planningConflict, withGoal, type PlanningForm } from '../planning';
import type { GoalWorkPackage, Todo } from '../../../types';

const base: PlanningForm = {
  goalId: 'g1', workPackageId: 'w1', complexity: 'M', suggestedEffort: 'high', ratingNote: 'Klar umrissen', openQuestion: '',
  decision: '',
};

describe('planningChanges', () => {
  it('sends nothing when nothing changed, so an AI rating is not relabelled as human', () => {
    expect(planningChanges(base, { ...base })).toEqual({});
    expect(planningChanges(base, { ...base, ratingNote: '  Klar umrissen ' })).toEqual({});
  });

  it('sends only the changed fields, trimmed, with empty text meaning "clear"', () => {
    expect(planningChanges(base, { ...base, suggestedEffort: 'xhigh', openQuestion: ' Wer entscheidet? ' }))
      .toEqual({ suggestedEffort: 'xhigh', openQuestion: 'Wer entscheidet?' });
    expect(planningChanges(base, { ...base, goalId: '' })).toEqual({ goalId: '' });
  });
});

// c60bf914: the work package of a TODO is chosen in the planning field, only from its own project
describe('work package in the planning field', () => {
  const wp = (id: string, projectId: string | null) => ({ id, projectId } as GoalWorkPackage);

  it('reads the package under its new name and under the name of an older backend', () => {
    const todo = (planning: Record<string, unknown>) => ({ planning } as unknown as Todo);
    expect(formOf(todo({ goalId: 'g1', workPackageId: 'w1' })).workPackageId).toBe('w1');
    expect(formOf(todo({ goalId: 'g1', milestoneId: 'w2' })).workPackageId).toBe('w2');
    expect(formOf(todo({ goalId: 'g1' })).workPackageId).toBe('');
  });

  it('sends a changed package, empty text meaning "none"', () => {
    expect(planningChanges(base, { ...base, workPackageId: 'w2' })).toEqual({ workPackageId: 'w2' });
    expect(planningChanges(base, { ...base, workPackageId: '' })).toEqual({ workPackageId: '' });
  });

  it('resets the package when the goal changes – it belongs to the old goal', () => {
    expect(withGoal(base, 'g2')).toEqual({ ...base, goalId: 'g2', workPackageId: '' });
    expect(withGoal(base, 'g1')).toBe(base);
    expect(planningChanges(base, withGoal(base, 'g2'))).toEqual({ goalId: 'g2', workPackageId: '' });
  });

  it('offers only packages of the TODO\'s project, and keeps the current one even if it does not fit', () => {
    const all = [wp('a', 'p1'), wp('b', 'p2'), wp('c', null), wp('d', 'p2')];
    expect(packageOptions(all, 'p1', '').map((item) => item.id)).toEqual(['a']);
    expect(packageOptions(all, 'p1', 'd').map((item) => item.id)).toEqual(['a', 'd']);
    expect(packageOptions(all, null, '').map((item) => item.id)).toEqual(['c']);
  });

  it('names the package the way the backend understands it: `milestoneId` before c60bf914', () => {
    expect(forBackend({ goalId: 'g1', workPackageId: 'w1' }, false)).toEqual({ goalId: 'g1', workPackageId: 'w1' });
    expect(forBackend({ goalId: 'g1', workPackageId: 'w1' }, true)).toEqual({ goalId: 'g1', milestoneId: 'w1' });
    expect(forBackend({ goalId: 'g1' }, true)).toEqual({ goalId: 'g1' });
  });
});

// 68a5e24e: the decision is part of the planning field
describe('decision in the planning field', () => {
  it('reads the decision, sends a changed one trimmed and empty text as "remove"', () => {
    const todo = { planning: { goalId: 'g1', decision: 'MIT', decisionState: 'DECIDED' } } as unknown as Todo;
    expect(formOf(todo).decision).toBe('MIT');
    const decided = { ...base, decision: 'MIT' };
    expect(planningChanges(decided, { ...decided, decision: ' Apache ' })).toEqual({ decision: 'Apache' });
    expect(planningChanges(decided, { ...decided, decision: '' })).toEqual({ decision: '' });
    expect(planningChanges(decided, { ...decided })).toEqual({});
  });

  it('refuses a new question and a decision in one go – the server would answer 400', () => {
    expect(planningConflict(planningChanges(base, { ...base, openQuestion: 'Which?', decision: 'This' }))).toBe(true);
    expect(planningConflict(planningChanges(base, { ...base, decision: 'This' }))).toBe(false);
    const asking = { ...base, openQuestion: 'Which?' };
    // answering the question and clearing it in the same step is fine
    expect(planningConflict(planningChanges(asking, { ...asking, openQuestion: '', decision: 'This' }))).toBe(false);
    // the unchanged question is not sent – the decision closes it on the server
    expect(planningChanges(asking, { ...asking, decision: 'This' })).toEqual({ decision: 'This' });
  });
});
