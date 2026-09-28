import type { GoalWorkPackage, Todo, UpdateTodoPlanningRequest } from '../../types';

export const COMPLEXITIES = ['S', 'M', 'L', 'XL'] as const;
export const EFFORTS = ['low', 'medium', 'high', 'xhigh'] as const;

export interface PlanningForm {
  goalId: string;
  /** Arbeitspaket im gewählten Ziel; leer = keins. */
  workPackageId: string;
  complexity: string;
  suggestedEffort: string;
  ratingNote: string;
  openQuestion: string;
  /** Entscheidung zur offenen Frage (68a5e24e); leer = keine. */
  decision: string;
}

export function formOf(todo: Todo): PlanningForm {
  const p = todo.planning;
  return {
    goalId: p?.goalId ?? '',
    // Ein älteres Backend nennt das Arbeitspaket noch `milestoneId`
    workPackageId: p?.workPackageId ?? p?.milestoneId ?? '',
    complexity: p?.complexity ?? '',
    suggestedEffort: p?.suggestedEffort ?? '',
    ratingNote: p?.ratingNote ?? '',
    openQuestion: p?.openQuestion ?? '',
    decision: p?.decision ?? '',
  };
}

/** Nur geänderte Felder – sonst würde ein Speichern ohne Änderung die KI-Einschätzung als menschlich markieren. */
export function planningChanges(before: PlanningForm, after: PlanningForm): UpdateTodoPlanningRequest {
  const data: UpdateTodoPlanningRequest = {};
  if (after.goalId !== before.goalId) data.goalId = after.goalId;
  if (after.workPackageId !== before.workPackageId) data.workPackageId = after.workPackageId;
  if (after.complexity !== before.complexity) data.complexity = after.complexity as UpdateTodoPlanningRequest['complexity'];
  if (after.suggestedEffort !== before.suggestedEffort) {
    data.suggestedEffort = after.suggestedEffort as UpdateTodoPlanningRequest['suggestedEffort'];
  }
  if (after.ratingNote.trim() !== before.ratingNote) data.ratingNote = after.ratingNote.trim();
  if (after.openQuestion.trim() !== before.openQuestion) data.openQuestion = after.openQuestion.trim();
  if (after.decision.trim() !== before.decision) data.decision = after.decision.trim();
  return data;
}

/**
 * Eine neue Frage und eine Entscheidung im selben Aufruf weist der Server ab (68a5e24e): Die
 * Entscheidung schließt die Frage. Erst entscheiden, dann neu fragen.
 */
export function planningConflict(changes: UpdateTodoPlanningRequest): boolean {
  return !!changes.openQuestion && !!changes.decision;
}


/** Ein anderes Ziel nimmt das Arbeitspaket weg – es gehört zum alten Ziel. */
export function withGoal(form: PlanningForm, goalId: string): PlanningForm {
  return goalId === form.goalId ? form : { ...form, goalId, workPackageId: '' };
}

/**
 * Arbeitspakete, die für ein TODO in Frage kommen (c60bf914): nur die seines Projekts – ein TODO ohne
 * Projekt nur solche ohne Projekt. Das bisherige bleibt wählbar, auch wenn es nicht (mehr) passt.
 */
export function packageOptions(workPackages: GoalWorkPackage[], projectId: string | null, current: string): GoalWorkPackage[] {
  return workPackages.filter((item) => (item.projectId ?? null) === (projectId ?? null) || item.id === current);
}

/**
 * Das Arbeitspaket im Aufruf: bei einem Backend vor c60bf914 noch unter seinem alten Namen
 * `milestoneId` – das neue Feld würde es stillschweigend übergehen.
 */
export function forBackend(changes: UpdateTodoPlanningRequest, legacyBackend: boolean | undefined): UpdateTodoPlanningRequest {
  if (!legacyBackend || changes.workPackageId === undefined) return changes;
  const { workPackageId, ...rest } = changes;
  return { ...rest, milestoneId: workPackageId };
}
