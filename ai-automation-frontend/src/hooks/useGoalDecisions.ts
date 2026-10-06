import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalKeys } from './useGoals';
import { invalidatePlanning, patchGoalDetail } from './useGoalTimeline';
import { applyDecisionToDetail } from '../components/goal/decision';
import type { Todo } from '../types';

/**
 * Decides the open question of a TODO or removes the decision (68a5e24e):
 * `PATCH /todos/{id}/planning` with `decision` – text closes the question, `''` removes the
 * decision. The goal page shows this immediately; if the call fails, it is rolled back.
 */
export function useDecideTodo(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  const td = t.goals.decision;
  return useMutation({
    mutationFn: ({ todoId, decision }: { todoId: string; decision: string }) =>
      api.patch<Todo>(`/todos/${todoId}/planning`, { decision }),
    onMutate: ({ todoId, decision }) =>
      patchGoalDetail(qc, goalId, (detail) => applyDecisionToDetail(detail, todoId, decision, new Date().toISOString())),
    onError: (err, _vars, context) => {
      if (context?.previous) qc.setQueryData(goalKeys.detail(goalId), context.previous);
      toast.error(apiErrorMessage(err, td.saveFailed));
    },
    onSuccess: (_todo, { decision }) => toast.success(decision.trim() ? td.saved : td.removed),
    onSettled: () => invalidatePlanning(qc),
  });
}
