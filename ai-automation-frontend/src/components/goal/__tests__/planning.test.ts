import { describe, it, expect } from 'vitest';
import { planningChanges, type PlanningForm } from '../planning';

const base: PlanningForm = { goalId: 'g1', complexity: 'M', suggestedEffort: 'high', ratingNote: 'Klar umrissen', openQuestion: '' };

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
