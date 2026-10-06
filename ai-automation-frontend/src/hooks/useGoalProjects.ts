import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage, apiErrorStatus } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalKeys, normalizeGoalDetail } from './useGoals';
import type { GoalDetail } from '../types';

/**
 * Projects of a goal (c60bf914): A goal is explicitly assigned to one or more projects.
 * Adding and reordering return the new detail, removing returns nothing.
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

/** Idempotent: a project that is already assigned stays where it is. */
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
 * Detaches a project from the goal. If work packages or TODOs still hang on it, the server answers with 409 –
 * the caller reports that itself (it then offers the cascade), hence no error toast here.
 * `cascade` deletes the project's work packages in this goal and detaches its TODOs from the goal.
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

/** New order of the goal's projects (`ids`: all project IDs). */
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
