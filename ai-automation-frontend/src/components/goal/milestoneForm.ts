import { dayOf, dayOfIsoDate, dueDateOfDay, isoDateOfDay } from './timelineLayout';
import type { CreateGoalMilestoneRequest, GoalMilestone, UpdateGoalMilestoneRequest } from '../../types';

/**
 * Formular eines Meilensteins (c60bf914) – im Dialog der Gliederung und in der Karte am Zeitstrahl
 * dasselbe: Titel, Datum (Pflicht, gilt bis 23:59:59 Ortszeit) und die verknüpften Arbeitspakete.
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

/** Bearbeiten: nur, was sich geändert hat; die Verknüpfungen gehen als ganze Menge. */
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

/** Nimmt ein Paket in die Auswahl auf oder wieder heraus. */
export function togglePackage(draft: MilestoneDraft, id: string): MilestoneDraft {
  return {
    ...draft,
    workPackageIds: draft.workPackageIds.includes(id) ? draft.workPackageIds.filter((other) => other !== id) : [...draft.workPackageIds, id],
  };
}

/** „Erreicht“ von Hand gibt es nur ohne verknüpfte Pakete – sonst entscheiden die Pakete. */
export const reachedByHand = (item: Pick<GoalMilestone, 'workPackageIds'>) => item.workPackageIds.length === 0;
