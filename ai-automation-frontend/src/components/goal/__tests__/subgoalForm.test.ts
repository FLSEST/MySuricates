import { describe, it, expect } from 'vitest';
import { createRequest, draftOf, isDraftValid, projectLocked, updateRequest } from '../subgoalForm';
import { dayOfIsoDate } from '../timelineLayout';
import type { GoalMilestone } from '../../../types';

const endOf = (iso: string) => new Date(`${iso}T23:59:59`).toISOString();

const legal: GoalMilestone = {
  id: 'm1', goalId: 'g1', title: 'Legal', dueDate: endOf('2026-10-15'), projectId: 'p1', position: 0, openQuestions: 0,
  state: 'OPEN', total: 0, done: 0, reachedAt: null, reopened: false, createdAt: '', updatedAt: '',
};

describe('subgoalForm (c60bf914)', () => {
  it('starts from the sub-goal or from the given project and day', () => {
    expect(draftOf(legal)).toEqual({ title: 'Legal', projectId: 'p1', date: '2026-10-15' });
    expect(draftOf({ ...legal, dueDate: null })).toMatchObject({ date: '' });
    expect(draftOf(undefined, { projectId: 'p2', day: dayOfIsoDate('2026-10-02') })).toEqual({ title: '', projectId: 'p2', date: '2026-10-02' });
    expect(draftOf(undefined)).toEqual({ title: '', projectId: '', date: '' });
  });

  it('needs a title and a project; the date is optional but must be complete', () => {
    expect(isDraftValid({ title: 'RC', projectId: 'p1', date: '' })).toBe(true);
    expect(isDraftValid({ title: '  ', projectId: 'p1', date: '' })).toBe(false);
    expect(isDraftValid({ title: 'RC', projectId: '', date: '' })).toBe(false);
    expect(isDraftValid({ title: 'RC', projectId: 'p1', date: '2026-10' })).toBe(false);
  });

  it('creates with project, and with a due date only when one is set', () => {
    expect(createRequest({ title: ' RC ', projectId: 'p1', date: '' })).toEqual({ title: 'RC', projectId: 'p1' });
    expect(createRequest({ title: 'RC', projectId: 'p1', date: '2026-10-08' }))
      .toEqual({ title: 'RC', projectId: 'p1', dueDate: endOf('2026-10-08') });
    expect(createRequest({ title: '', projectId: 'p1', date: '' })).toBeNull();
  });

  it('updates only what changed: an empty date clears it, another project moves it', () => {
    expect(updateRequest(legal, draftOf(legal))).toEqual({});
    expect(updateRequest(legal, { ...draftOf(legal), date: '' })).toEqual({ dueDate: '' });
    expect(updateRequest(legal, { ...draftOf(legal), date: '2026-10-16', title: 'Legal review' }))
      .toEqual({ title: 'Legal review', dueDate: endOf('2026-10-16') });
    expect(updateRequest({ ...legal, dueDate: null }, { ...draftOf(legal), projectId: 'p2' }))
      .toEqual({ dueDate: endOf('2026-10-15'), projectId: 'p2' });
  });

  it('locks the project as soon as a TODO is assigned', () => {
    expect(projectLocked(legal)).toBe(false);
    expect(projectLocked({ ...legal, total: 1 })).toBe(true);
    expect(projectLocked(undefined)).toBe(false);
  });
});
