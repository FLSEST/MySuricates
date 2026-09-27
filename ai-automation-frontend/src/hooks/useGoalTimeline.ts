import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalKeys } from './useGoals';
import { applyPlan, type PlanChanges } from '../components/goal/timelineLayout';
import type { GoalDetail, UpdateTodoPlanningRequest } from '../types';

/**
 * Planung an einem Ziel (TODO e192df18, c60bf914): TODOs einplanen, Arbeitspakete und Meilensteine
 * pflegen. Danach ist neu zu laden, was davon abhängt – Ziele (Fortschritt, Zustände) und TODO-Listen
 * samt Cockpit („Braucht dich“).
 */
export function invalidatePlanning(qc: QueryClient) {
  qc.invalidateQueries({ queryKey: goalKeys.all });
  qc.invalidateQueries({ queryKey: ['todos'] });
}

/**
 * Ändert das gespeicherte Detail eines Ziels sofort (optimistisch) und gibt den alten Stand zum
 * Zurücksetzen zurück, falls der Aufruf scheitert.
 */
export async function patchGoalDetail(qc: QueryClient, goalId: string, patch: (detail: GoalDetail) => GoalDetail) {
  const key = goalKeys.detail(goalId);
  await qc.cancelQueries({ queryKey: key });
  const previous = qc.getQueryData<GoalDetail>(key);
  if (previous) qc.setQueryData<GoalDetail>(key, patch(previous));
  return { previous };
}

/**
 * Das Arbeitspaket im Planungsaufruf: `workPackageId`, bei einem Backend vor c60bf914 (`legacyBackend`)
 * noch unter seinem alten Namen `milestoneId` – das neue Feld würde es stillschweigend übergehen.
 */
export function workPackageField(workPackageId: string, legacyBackend: boolean | undefined): UpdateTodoPlanningRequest {
  return legacyBackend ? { milestoneId: workPackageId } : { workPackageId };
}

export interface PlanGoalTodo {
  todoId: string;
  changes: PlanChanges;
  /** Aus dem Menü eines Chips: mit Bestätigung, denn dort sieht man den Chip nicht springen. */
  announce?: boolean;
}

/**
 * Fälligkeit (`PUT /todos/{id}`) und Arbeitspaket (`PATCH …/planning`) eines TODOs. Der Chip springt
 * sofort an die neue Stelle; scheitert ein Aufruf, geht er zurück.
 */
export function usePlanGoalTodo(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: async ({ todoId, changes }: PlanGoalTodo) => {
      const legacy = qc.getQueryData<GoalDetail>(goalKeys.detail(goalId))?.legacyBackend;
      if (changes.dueDate !== undefined) await api.put(`/todos/${todoId}`, { dueDate: changes.dueDate });
      if (changes.workPackageId !== undefined) {
        await api.patch(`/todos/${todoId}/planning`, workPackageField(changes.workPackageId, legacy));
      }
    },
    onMutate: ({ todoId, changes }) =>
      patchGoalDetail(qc, goalId, (detail) => ({ ...detail, todos: applyPlan(detail.todos, todoId, changes) })),
    onError: (err, _plan, context) => {
      if (context?.previous) qc.setQueryData(goalKeys.detail(goalId), context.previous);
      toast.error(apiErrorMessage(err, t.goals.timeline.saveFailed));
    },
    onSuccess: (_result, { announce }) => {
      if (announce) toast.success(t.goals.timeline.planned);
    },
    onSettled: () => invalidatePlanning(qc),
  });
}
