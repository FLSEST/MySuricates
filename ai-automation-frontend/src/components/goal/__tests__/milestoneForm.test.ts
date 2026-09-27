import { describe, it, expect } from 'vitest';
import {
  createMilestoneRequest, isMilestoneDraftValid, milestoneDraftOf, reachedByHand, togglePackage, updateMilestoneRequest,
} from '../milestoneForm';
import { dayOfIsoDate } from '../timelineLayout';
import type { GoalMilestone } from '../../../types';

const endOf = (iso: string) => new Date(`${iso}T23:59:59`).toISOString();

const freeze: GoalMilestone = {
  id: 'm1', goalId: 'g1', title: 'Code-Freeze', dueDate: endOf('2026-10-15'), state: 'OPEN', workPackageIds: ['w1', 'w2'],
  packagesTotal: 2, packagesDone: 1, reachedAt: null, reopened: false, createdAt: '', updatedAt: '',
};

describe('milestoneForm (c60bf914)', () => {
  it('starts from the milestone or from an empty one on the given day', () => {
    expect(milestoneDraftOf(freeze)).toEqual({ title: 'Code-Freeze', date: '2026-10-15', workPackageIds: ['w1', 'w2'] });
    expect(milestoneDraftOf(undefined, dayOfIsoDate('2026-10-02'))).toEqual({ title: '', date: '2026-10-02', workPackageIds: [] });
  });

  it('needs a title and a date', () => {
    expect(isMilestoneDraftValid({ title: 'RC', date: '2026-10-02', workPackageIds: [] })).toBe(true);
    expect(isMilestoneDraftValid({ title: ' ', date: '2026-10-02', workPackageIds: [] })).toBe(false);
    expect(isMilestoneDraftValid({ title: 'RC', date: '', workPackageIds: [] })).toBe(false);
  });

  it('creates with the end of its day and the linked packages, if any', () => {
    expect(createMilestoneRequest({ title: ' RC ', date: '2026-10-02', workPackageIds: [] }))
      .toEqual({ title: 'RC', dueDate: endOf('2026-10-02') });
    expect(createMilestoneRequest({ title: 'RC', date: '2026-10-02', workPackageIds: ['w1'] }))
      .toEqual({ title: 'RC', dueDate: endOf('2026-10-02'), workPackageIds: ['w1'] });
  });

  it('updates only what changed; the links go as a whole set, whatever their order', () => {
    expect(updateMilestoneRequest(freeze, milestoneDraftOf(freeze))).toEqual({});
    expect(updateMilestoneRequest(freeze, { ...milestoneDraftOf(freeze), workPackageIds: ['w2', 'w1'] })).toEqual({});
    expect(updateMilestoneRequest(freeze, { title: 'Freeze', date: '2026-10-16', workPackageIds: ['w1'] }))
      .toEqual({ title: 'Freeze', dueDate: endOf('2026-10-16'), workPackageIds: ['w1'] });
  });

  it('toggles a package in and out of the selection', () => {
    const draft = milestoneDraftOf(freeze);
    expect(togglePackage(draft, 'w3').workPackageIds).toEqual(['w1', 'w2', 'w3']);
    expect(togglePackage(draft, 'w1').workPackageIds).toEqual(['w2']);
  });

  it('is ticked off by hand only without linked packages', () => {
    expect(reachedByHand(freeze)).toBe(false);
    expect(reachedByHand({ ...freeze, workPackageIds: [] })).toBe(true);
  });
});
