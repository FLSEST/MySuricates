import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage, apiErrorStatus } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalKeys, normalizeGoalDetail } from './useGoals';
import type { GoalDetail } from '../types';

/**
 * Projekte eines Ziels (c60bf914): Ein Ziel wird ausdrücklich einem oder mehreren Projekten
 * zugeordnet. Hinzufügen und Umsortieren liefern das neue Detail, Entfernen nichts.
 */
function refresh(qc: QueryClient) {
  qc.invalidateQueries({ queryKey: goalKeys.all });
  qc.invalidateQueries({ queryKey: ['todos'] });
}

function useDetailReply(goalId: string) {
  const qc = useQueryClient();
  return (detail: GoalDetail | undefined) => {
    if (detail && typeof detail === 'object' && 'goal' in detail) {
      qc.setQueryData(goalKeys.detail(goalId), normalizeGoalDetail(detail));
    }
    refresh(qc);
  };
}

/** Idempotent: ein schon zugeordnetes Projekt bleibt, wo es ist. */
export function useAddGoalProject(goalId: string) {
  const toast = useToast();
  const t = useT();
  const onDetail = useDetailReply(goalId);
  return useMutation({
    mutationFn: (projectId: string) => api.post<GoalDetail>(`/goals/${goalId}/projects`, { projectId }),
    onSuccess: (detail) => {
      onDetail(detail);
      toast.success(t.goals.projects.added);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.projects.saveFailed)),
  });
}

/**
 * Löst ein Projekt vom Ziel. Hängen noch Arbeitspakete oder TODOs daran, antwortet der Server mit 409 –
 * das meldet der Aufrufer selbst (er bietet dann die Kaskade an), deshalb hier kein Fehler-Toast.
 * `cascade` löscht die Arbeitspakete des Projekts in diesem Ziel und löst seine TODOs vom Ziel.
 */
export function useRemoveGoalProject(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ projectId, cascade }: { projectId: string; cascade: boolean }) =>
      api.delete<void>(`/goals/${goalId}/projects/${encodeURIComponent(projectId)}?cascade=${cascade}`),
    onSuccess: () => {
      refresh(qc);
      toast.success(t.goals.projects.removed);
    },
    onError: (err) => {
      if (apiErrorStatus(err) !== 409) toast.error(apiErrorMessage(err, t.goals.projects.saveFailed));
    },
  });
}

/** Neue Reihenfolge der Projekte des Ziels (`ids`: alle Projekt-IDs). */
export function useReorderGoalProjects(goalId: string) {
  const toast = useToast();
  const t = useT();
  const onDetail = useDetailReply(goalId);
  return useMutation({
    mutationFn: (ids: string[]) => api.put<GoalDetail>(`/goals/${goalId}/projects/order`, { ids }),
    onSuccess: onDetail,
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.projects.saveFailed)),
  });
}
