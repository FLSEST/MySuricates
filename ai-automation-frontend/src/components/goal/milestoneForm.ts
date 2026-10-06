import { dayOf, dayOfIsoDate, dueDateOfDay, isoDateOfDay } from './timelineLayout';
import type { CreateGoalMilestoneRequest, GoalMilestone, UpdateGoalMilestoneRequest } from '../../types';

/**
 * Form of a milestone (c60bf914) - the same in the outline dialog and in the card on the timeline:
 * title, date (required, valid until 23:59:59 local time) and the linked work packages.
 */
export interface MilestoneDraft {
  title: string;
  /** `YYYY-MM-DD`. */
  date: string;
  workPackageIds: string[];
}

export function milestoneDraftOf(item: GoalMilestone | undefined, day?: number | null): MilestoneDraft {
  if (item) return { title: item.title, date: isoDateOfDay(dayOf(item.dueDate)), workPackageIds: [...item.workPackageIds] };
  return { title: '', date: day != null ? isoDateOfDay(day) : '', workPackageIds: [] };
}

export function isMilestoneDraftValid(draft: MilestoneDraft): boolean {
  return draft.title.trim() !== '' && dayOfIsoDate(draft.date) !== null;
}

export function createMilestoneRequest(draft: MilestoneDraft): CreateGoalMilestoneRequest | null {
  const day = dayOfIsoDate(draft.date);
  if (!isMilestoneDraftValid(draft) || day === null) return null;
  return {
    title: draft.title.trim(),
    dueDate: dueDateOfDay(day),
    ...(draft.workPackageIds.length > 0 ? { workPackageIds: draft.workPackageIds } : {}),
  };
}

const sameSet = (a: string[], b: string[]) => a.length === b.length && a.every((id) => b.includes(id));

/** Editing: only what has changed; the links go as a whole set. */
export function updateMilestoneRequest(item: GoalMilestone, draft: MilestoneDraft): UpdateGoalMilestoneRequest | null {
  const day = dayOfIsoDate(draft.date);
  if (!isMilestoneDraftValid(draft) || day === null) return null;
  const data: UpdateGoalMilestoneRequest = {};
  const title = draft.title.trim();
  if (title !== item.title) data.title = title;
  if (day !== dayOf(item.dueDate)) data.dueDate = dueDateOfDay(day);
  if (!sameSet(draft.workPackageIds, item.workPackageIds)) data.workPackageIds = draft.workPackageIds;
  return data;
}

/** Adds a package to the selection or removes it again. */
export function togglePackage(draft: MilestoneDraft, id: string): MilestoneDraft {
  return {
    ...draft,
    workPackageIds: draft.workPackageIds.includes(id) ? draft.workPackageIds.filter((other) => other !== id) : [...draft.workPackageIds, id],
  };
}

/** „Erreicht“ by hand exists only without linked packages - otherwise the packages decide. */
export const reachedByHand = (item: Pick<GoalMilestone, 'workPackageIds'>) => item.workPackageIds.length === 0;
