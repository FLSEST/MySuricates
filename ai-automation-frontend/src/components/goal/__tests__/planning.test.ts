import { describe, it, expect } from 'vitest';
import { planningChanges, subgoalOptions, withGoal, type PlanningForm } from '../planning';
import type { GoalMilestone } from '../../../types';

const base: PlanningForm = { goalId: 'g1', milestoneId: 'm1', complexity: 'M', suggestedEffort: 'high', ratingNote: 'Klar umrissen', openQuestion: '' };

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

// c60bf914: the sub-goal of a TODO is chosen in the planning field, only from its own project
describe('sub-goal in the planning field', () => {
  const ms = (id: string, projectId: string | null) => ({ id, projectId } as GoalMilestone);

  it('sends a changed sub-goal, empty text meaning "none"', () => {
    expect(planningChanges(base, { ...base, milestoneId: 'm2' })).toEqual({ milestoneId: 'm2' });
    expect(planningChanges(base, { ...base, milestoneId: '' })).toEqual({ milestoneId: '' });
  });

  it('resets the sub-goal when the goal changes – it belongs to the old goal', () => {
    expect(withGoal(base, 'g2')).toEqual({ ...base, goalId: 'g2', milestoneId: '' });
    expect(withGoal(base, 'g1')).toBe(base);
    expect(planningChanges(base, withGoal(base, 'g2'))).toEqual({ goalId: 'g2', milestoneId: '' });
  });

  it('offers only sub-goals of the TODO\'s project, and keeps the current one even if it does not fit', () => {
    const all = [ms('a', 'p1'), ms('b', 'p2'), ms('c', null), ms('d', 'p2')];
    expect(subgoalOptions(all, 'p1', '').map((m) => m.id)).toEqual(['a']);
    expect(subgoalOptions(all, 'p1', 'd').map((m) => m.id)).toEqual(['a', 'd']);
    expect(subgoalOptions(all, null, '').map((m) => m.id)).toEqual(['c']);
  });
});
