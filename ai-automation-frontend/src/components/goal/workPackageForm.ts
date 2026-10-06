import { dayOfIsoDate, isoDateOfDay, packageRange, rangeRequest } from './timelineLayout';
import type { CreateGoalWorkPackageRequest, GoalWorkPackage, UpdateGoalWorkPackageRequest } from '../../types';

/**
 * Form of a work package (c60bf914) - the same in the outline dialog and in the card on the
 * timeline: title, project, period optional (start and end only together). The start counts from
 * 00:00, the end until 23:59:59 local time.
 */
export interface WorkPackageDraft {
  title: string;
  projectId: string;
  /** `YYYY-MM-DD` or empty. */
  start: string;
  /** `YYYY-MM-DD` or empty. */
  end: string;
}

/** Title at most as long as the backend accepts. */
export const MAX_TITLE = 200;

export function packageDraftOf(
  item: GoalWorkPackage | undefined, defaults: { projectId?: string | null; start?: number | null; end?: number | null } = {},
): WorkPackageDraft {
  if (item) {
    const range = packageRange(item);
    return {
      title: item.title,
      projectId: item.projectId ?? '',
      start: range ? isoDateOfDay(range.start) : '',
      end: range ? isoDateOfDay(range.end) : '',
    };
  }
  return {
    title: '',
    projectId: defaults.projectId ?? '',
    start: defaults.start != null ? isoDateOfDay(defaults.start) : '',
    end: defaults.end != null ? isoDateOfDay(defaults.end) : '',
  };
}

/** What is wrong with the period: only one of the two, a half-typed date or the end before the start. */
export type RangeProblem = 'incomplete' | 'order' | null;

export function rangeProblem(draft: Pick<WorkPackageDraft, 'start' | 'end'>): RangeProblem {
  if (draft.start === '' && draft.end === '') return null;
  const start = dayOfIsoDate(draft.start);
  const end = dayOfIsoDate(draft.end);
  if (start === null || end === null) return 'incomplete';
  return end < start ? 'order' : null;
}

function draftRange(draft: WorkPackageDraft) {
  const start = dayOfIsoDate(draft.start);
  const end = dayOfIsoDate(draft.end);
  return start !== null && end !== null ? { start, end } : null;
}

export function isPackageDraftValid(draft: WorkPackageDraft): boolean {
  return draft.title.trim() !== '' && draft.projectId !== '' && rangeProblem(draft) === null;
}

/** Create: without a period, start and end are missing entirely. null as long as the form is invalid. */
export function createPackageRequest(draft: WorkPackageDraft): CreateGoalWorkPackageRequest | null {
  if (!isPackageDraftValid(draft)) return null;
  const range = draftRange(draft);
  return { title: draft.title.trim(), projectId: draft.projectId, ...(range ? rangeRequest(range) : {}) };
}

/**
 * Edit: only what has changed - a cleared period sends start and end empty, a
 * changed one sends both anew (the server checks them together). null as long as the form is invalid.
 */
export function updatePackageRequest(item: GoalWorkPackage, draft: WorkPackageDraft): UpdateGoalWorkPackageRequest | null {
  if (!isPackageDraftValid(draft)) return null;
  const data: UpdateGoalWorkPackageRequest = {};
  const title = draft.title.trim();
  if (title !== item.title) data.title = title;
  const before = packageRange(item);
  const after = draftRange(draft);
  if (before?.start !== after?.start || before?.end !== after?.end) Object.assign(data, rangeRequest(after));
  if (draft.projectId !== (item.projectId ?? '')) data.projectId = draft.projectId;
  return data;
}

/** The project of a package changes only as long as no TODO is assigned to it. */
export function projectLocked(item: GoalWorkPackage | undefined): boolean {
  return !!item && item.total > 0;
}
