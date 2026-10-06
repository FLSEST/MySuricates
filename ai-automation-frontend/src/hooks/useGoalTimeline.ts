import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalKeys } from './useGoals';
import { applyPlan, type PlanChanges } from '../components/goal/timelineLayout';
import type { GoalDetail, UpdateTodoPlanningRequest } from '../types';

/**
 * Planning on a goal (TODO e192df18, c60bf914): schedule TODOs, maintain work packages and milestones.
 * Afterwards whatever depends on it must be reloaded – goals (progress, states) and TODO lists
 * including the cockpit ("Braucht dich").
 */
export function invalidatePlanning(qc: QueryClient) {
  qc.invalidateQueries({ queryKey: goalKeys.all });
  qc.invalidateQueries({ queryKey: ['todos'] });
}

/**
 * Changes the stored detail of a goal immediately (optimistic) and returns the old state for
 * rollback in case the call fails.
 */
export async function patchGoalDetail(qc: QueryClient, goalId: string, patch: (detail: GoalDetail) => GoalDetail) {
  const key = goalKeys.detail(goalId);
  await qc.cancelQueries({ queryKey: key });
  const previous = qc.getQueryData<GoalDetail>(key);
  if (previous) qc.setQueryData<GoalDetail>(key, patch(previous));
  return { previous };
}

/**
 * The work package in the planning call: `workPackageId`, for a backend before c60bf914 (`legacyBackend`)
 * still under its old name `milestoneId` – the new field would silently ignore it.
 */
export function workPackageField(workPackageId: string, legacyBackend: boolean | undefined): UpdateTodoPlanningRequest {
  return legacyBackend ? { milestoneId: workPackageId } : { workPackageId };
}

export interface PlanGoalTodo {
  todoId: string;
  changes: PlanChanges;
  /** From a chip's menu: with confirmation, because there you do not see the chip jump. */
  announce?: boolean;
}

/**
 * Due date (`PUT /todos/{id}`) and work package (`PATCH …/planning`) of a TODO. The chip jumps
 * immediately to the new place; if a call fails, it goes back.
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
