import { dayOfIsoDate, isoDateOfDay, packageRange, rangeRequest } from './timelineLayout';
import type { CreateGoalWorkPackageRequest, GoalWorkPackage, UpdateGoalWorkPackageRequest } from '../../types';

/**
 * Formular eines Arbeitspakets (c60bf914) – im Dialog der Gliederung und in der Karte am Zeitstrahl
 * dasselbe: Titel, Projekt, Zeitraum optional (Beginn und Ende nur zusammen). Der Beginn gilt ab
 * 00:00, das Ende bis 23:59:59 Ortszeit.
 */
export interface WorkPackageDraft {
  title: string;
  projectId: string;
  /** `YYYY-MM-DD` oder leer. */
  start: string;
  /** `YYYY-MM-DD` oder leer. */
  end: string;
}

/** Titel höchstens so lang, wie das Backend annimmt. */
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

/** Was am Zeitraum nicht stimmt: nur eins von beiden, ein halb getipptes Datum oder das Ende vor dem Beginn. */
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

/** Anlegen: ohne Zeitraum fehlen Beginn und Ende ganz. null, solange das Formular nicht stimmt. */
export function createPackageRequest(draft: WorkPackageDraft): CreateGoalWorkPackageRequest | null {
  if (!isPackageDraftValid(draft)) return null;
  const range = draftRange(draft);
  return { title: draft.title.trim(), projectId: draft.projectId, ...(range ? rangeRequest(range) : {}) };
}

/**
 * Bearbeiten: nur, was sich geändert hat – ein geleerter Zeitraum schickt Beginn und Ende leer, ein
 * geänderter beide neu (der Server prüft sie zusammen). null, solange das Formular nicht stimmt.
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

/** Das Projekt eines Pakets wechselt nur, solange ihm kein TODO zugeordnet ist. */
export function projectLocked(item: GoalWorkPackage | undefined): boolean {
  return !!item && item.total > 0;
}
