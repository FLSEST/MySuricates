import type { GoalWorkPackage, Todo, UpdateTodoPlanningRequest } from '../../types';

export const COMPLEXITIES = ['S', 'M', 'L', 'XL'] as const;
export const EFFORTS = ['low', 'medium', 'high', 'xhigh'] as const;

export interface PlanningForm {
  goalId: string;
  /** Work package in the chosen goal; empty = none. */
  workPackageId: string;
  complexity: string;
  suggestedEffort: string;
  ratingNote: string;
  openQuestion: string;
  /** Decision on the open question (68a5e24e); empty = none. */
  decision: string;
}

/** Formular ohne TODO (Dialog geschlossen). */
export const EMPTY_PLANNING: PlanningForm = {
  goalId: '', workPackageId: '', complexity: '', suggestedEffort: '', ratingNote: '', openQuestion: '', decision: '',
};

export function formOf(todo: Todo): PlanningForm {
  const p = todo.planning;
  return {
    goalId: p?.goalId ?? '',
    // An older backend still calls the work package `milestoneId`
    workPackageId: p?.workPackageId ?? p?.milestoneId ?? '',
    complexity: p?.complexity ?? '',
    suggestedEffort: p?.suggestedEffort ?? '',
    ratingNote: p?.ratingNote ?? '',
    openQuestion: p?.openQuestion ?? '',
    decision: p?.decision ?? '',
  };
}

/** Only changed fields - otherwise saving without changes would mark the AI estimate as human. */
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
 * The server rejects a new question and a decision in the same call (68a5e24e): the
 * decision closes the question. Decide first, then ask anew.
 */
export function planningConflict(changes: UpdateTodoPlanningRequest): boolean {
  return !!changes.openQuestion && !!changes.decision;
}


/** Another goal takes the work package away - it belongs to the old goal. */
export function withGoal(form: PlanningForm, goalId: string): PlanningForm {
  return goalId === form.goalId ? form : { ...form, goalId, workPackageId: '' };
}

/**
 * Work packages that qualify for a TODO (c60bf914): only those of its project - a TODO without a
 * project only those without a project. The current one stays selectable even if it no longer fits.
 */
export function packageOptions(workPackages: GoalWorkPackage[], projectId: string | null, current: string): GoalWorkPackage[] {
  return workPackages.filter((item) => (item.projectId ?? null) === (projectId ?? null) || item.id === current);
}

/**
 * The work package in the call: for a backend before c60bf914 still under its old name
 * `milestoneId` - the new field would silently ignore it.
 */
export function forBackend(changes: UpdateTodoPlanningRequest, legacyBackend: boolean | undefined): UpdateTodoPlanningRequest {
  if (!legacyBackend || changes.workPackageId === undefined) return changes;
  const { workPackageId, ...rest } = changes;
  return { ...rest, milestoneId: workPackageId };
}
