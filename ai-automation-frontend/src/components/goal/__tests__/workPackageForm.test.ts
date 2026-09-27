import { describe, it, expect } from 'vitest';
import {
  createPackageRequest, isPackageDraftValid, packageDraftOf, projectLocked, rangeProblem, updatePackageRequest,
} from '../workPackageForm';
import { dayOfIsoDate } from '../timelineLayout';
import type { GoalWorkPackage } from '../../../types';

const endOf = (iso: string) => new Date(`${iso}T23:59:59`).toISOString();
const startOf = (iso: string) => new Date(`${iso}T00:00:00`).toISOString();

const repo: GoalWorkPackage = {
  id: 'w1', goalId: 'g1', projectId: 'p1', title: 'Repo sauber', startDate: startOf('2026-10-12'), endDate: endOf('2026-10-18'),
  position: 0, state: 'OPEN', total: 0, done: 0, openQuestions: 0, completedAt: null, reopened: false, createdAt: '', updatedAt: '',
};

describe('workPackageForm (c60bf914)', () => {
  it('starts from the package or from the given project and days', () => {
    expect(packageDraftOf(repo)).toEqual({ title: 'Repo sauber', projectId: 'p1', start: '2026-10-12', end: '2026-10-18' });
    expect(packageDraftOf({ ...repo, startDate: null, endDate: null })).toMatchObject({ start: '', end: '' });
    expect(packageDraftOf(undefined, { projectId: 'p2', start: dayOfIsoDate('2026-10-02'), end: dayOfIsoDate('2026-10-08') }))
      .toEqual({ title: '', projectId: 'p2', start: '2026-10-02', end: '2026-10-08' });
    expect(packageDraftOf(undefined)).toEqual({ title: '', projectId: '', start: '', end: '' });
  });

  it('needs a title and a project; start and end only together and in order', () => {
    const draft = { title: 'RC', projectId: 'p1', start: '', end: '' };
    expect(isPackageDraftValid(draft)).toBe(true);
    expect(isPackageDraftValid({ ...draft, title: '  ' })).toBe(false);
    expect(isPackageDraftValid({ ...draft, projectId: '' })).toBe(false);
    expect(rangeProblem({ start: '2026-10-12', end: '' })).toBe('incomplete');
    expect(rangeProblem({ start: '', end: '2026-10-12' })).toBe('incomplete');
    expect(rangeProblem({ start: '2026-10-12', end: '2026-10' })).toBe('incomplete');
    expect(rangeProblem({ start: '2026-10-12', end: '2026-10-11' })).toBe('order');
    expect(rangeProblem({ start: '2026-10-12', end: '2026-10-12' })).toBeNull();
    expect(isPackageDraftValid({ ...draft, start: '2026-10-12' })).toBe(false);
  });

  it('creates with project, and with start and end only when a span is set', () => {
    expect(createPackageRequest({ title: ' RC ', projectId: 'p1', start: '', end: '' })).toEqual({ title: 'RC', projectId: 'p1' });
    expect(createPackageRequest({ title: 'RC', projectId: 'p1', start: '2026-10-12', end: '2026-10-18' }))
      .toEqual({ title: 'RC', projectId: 'p1', startDate: startOf('2026-10-12'), endDate: endOf('2026-10-18') });
    expect(createPackageRequest({ title: '', projectId: 'p1', start: '', end: '' })).toBeNull();
  });

  it('updates only what changed: a cleared span sends both empty, a changed one both new, another project moves it', () => {
    expect(updatePackageRequest(repo, packageDraftOf(repo))).toEqual({});
    expect(updatePackageRequest(repo, { ...packageDraftOf(repo), start: '', end: '' })).toEqual({ startDate: '', endDate: '' });
    expect(updatePackageRequest(repo, { ...packageDraftOf(repo), end: '2026-10-20', title: 'Repo aufgeräumt' }))
      .toEqual({ title: 'Repo aufgeräumt', startDate: startOf('2026-10-12'), endDate: endOf('2026-10-20') });
    expect(updatePackageRequest({ ...repo, startDate: null, endDate: null }, { ...packageDraftOf(repo), projectId: 'p2' }))
      .toEqual({ startDate: startOf('2026-10-12'), endDate: endOf('2026-10-18'), projectId: 'p2' });
    expect(updatePackageRequest(repo, { ...packageDraftOf(repo), end: '' })).toBeNull();
  });

  it('locks the project as soon as a TODO is assigned', () => {
    expect(projectLocked(repo)).toBe(false);
    expect(projectLocked({ ...repo, total: 1 })).toBe(true);
    expect(projectLocked(undefined)).toBe(false);
  });
});
