import type { Todo, UpdateTodoPlanningRequest } from '../../types';

export const COMPLEXITIES = ['S', 'M', 'L', 'XL'] as const;
export const EFFORTS = ['low', 'medium', 'high', 'xhigh'] as const;

export interface PlanningForm {
  goalId: string;
  complexity: string;
  suggestedEffort: string;
  ratingNote: string;
  openQuestion: string;
}

export function formOf(todo: Todo): PlanningForm {
  const p = todo.planning;
  return {
    goalId: p?.goalId ?? '',
    complexity: p?.complexity ?? '',
    suggestedEffort: p?.suggestedEffort ?? '',
    ratingNote: p?.ratingNote ?? '',
    openQuestion: p?.openQuestion ?? '',
  };
}

/** Nur geänderte Felder – sonst würde ein Speichern ohne Änderung die KI-Einschätzung als menschlich markieren. */
export function planningChanges(before: PlanningForm, after: PlanningForm): UpdateTodoPlanningRequest {
  const data: UpdateTodoPlanningRequest = {};
  if (after.goalId !== before.goalId) data.goalId = after.goalId;
  if (after.complexity !== before.complexity) data.complexity = after.complexity as UpdateTodoPlanningRequest['complexity'];
  if (after.suggestedEffort !== before.suggestedEffort) {
    data.suggestedEffort = after.suggestedEffort as UpdateTodoPlanningRequest['suggestedEffort'];
  }
  if (after.ratingNote.trim() !== before.ratingNote) data.ratingNote = after.ratingNote.trim();
  if (after.openQuestion.trim() !== before.openQuestion) data.openQuestion = after.openQuestion.trim();
  return data;
}

