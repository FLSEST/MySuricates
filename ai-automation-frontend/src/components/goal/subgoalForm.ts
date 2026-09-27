import { dayOf, dayOfIsoDate, dueDateOfDay, isoDateOfDay } from './timelineLayout';
import type { CreateGoalMilestoneRequest, GoalMilestone, UpdateGoalMilestoneRequest } from '../../types';

/**
 * Formular eines Teilziels (c60bf914) – im Dialog der Gliederung und im Popover des Zeitstrahls
 * dasselbe: Titel, Projekt, Datum optional. Das Datum gilt wie eine Fälligkeit bis 23:59:59 Ortszeit.
 */
export interface SubgoalDraft {
  title: string;
  projectId: string;
  /** `YYYY-MM-DD` oder leer (= ohne Datum). */
  date: string;
}

/** Titel höchstens so lang, wie das Backend annimmt. */
export const MAX_SUBGOAL_TITLE = 200;

export function draftOf(milestone: GoalMilestone | undefined, defaults: { projectId?: string | null; day?: number | null } = {}): SubgoalDraft {
  if (milestone) {
    return {
      title: milestone.title,
      projectId: milestone.projectId ?? '',
      date: milestone.dueDate ? isoDateOfDay(dayOf(milestone.dueDate)) : '',
    };
  }
  return {
    title: '',
    projectId: defaults.projectId ?? '',
    date: defaults.day !== null && defaults.day !== undefined ? isoDateOfDay(defaults.day) : '',
  };
}

/** Ein leeres Datum ist gültig (ohne Datum), ein halb getipptes nicht. */
function dueDateOf(date: string): string | null | undefined {
  if (date === '') return null;
  const day = dayOfIsoDate(date);
  return day === null ? undefined : dueDateOfDay(day);
}

export function isDraftValid(draft: SubgoalDraft): boolean {
  return draft.title.trim() !== '' && draft.projectId !== '' && dueDateOf(draft.date) !== undefined;
}

/** Anlegen: ohne Datum fehlt `dueDate` ganz. null, solange das Formular nicht vollständig ist. */
export function createRequest(draft: SubgoalDraft): CreateGoalMilestoneRequest | null {
  if (!isDraftValid(draft)) return null;
  const dueDate = dueDateOf(draft.date);
  return { title: draft.title.trim(), projectId: draft.projectId, ...(dueDate ? { dueDate } : {}) };
}

/**
 * Bearbeiten: nur, was sich geändert hat – `dueDate: ''` entfernt das Datum, ein anderer Tag setzt
 * ihn neu. null, solange das Formular nicht vollständig ist.
 */
export function updateRequest(milestone: GoalMilestone, draft: SubgoalDraft): UpdateGoalMilestoneRequest | null {
  if (!isDraftValid(draft)) return null;
  const data: UpdateGoalMilestoneRequest = {};
  const title = draft.title.trim();
  if (title !== milestone.title) data.title = title;
  const day = draft.date === '' ? null : dayOfIsoDate(draft.date);
  const before = milestone.dueDate ? dayOf(milestone.dueDate) : null;
  if (day !== before) data.dueDate = day === null ? '' : dueDateOfDay(day);
  if (draft.projectId !== (milestone.projectId ?? '')) data.projectId = draft.projectId;
  return data;
}

/** Das Projekt eines Teilziels wechselt nur, solange ihm kein TODO zugeordnet ist. */
export function projectLocked(milestone: GoalMilestone | undefined): boolean {
  return !!milestone && milestone.total > 0;
}
