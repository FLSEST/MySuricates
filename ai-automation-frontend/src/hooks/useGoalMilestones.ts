import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalKeys } from './useGoals';
import { invalidatePlanning, patchGoalDetail } from './useGoalTimeline';
import type { CreateGoalMilestoneRequest, GoalMilestone, UpdateGoalMilestoneRequest } from '../types';

/**
 * Meilensteine eines Ziels (c60bf914): Termine fürs ganze Ziel, optional mit Arbeitspaketen verknüpft –
 * erreicht, wenn die verknüpften Pakete abgeschlossen sind, ohne Verknüpfung von Hand.
 */
export function useCreateMilestone(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: CreateGoalMilestoneRequest) => api.post<GoalMilestone>(`/goals/${goalId}/milestones`, data),
    onSuccess: () => {
      invalidatePlanning(qc);
      toast.success(t.goals.milestones.saved);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.milestones.saveFailed)),
  });
}

/** Die Änderung im gespeicherten Meilenstein – Datum und Verknüpfungen sofort sichtbar. */
function applyToMilestone(item: GoalMilestone, data: UpdateGoalMilestoneRequest): GoalMilestone {
  return {
    ...item,
    ...(data.title !== undefined ? { title: data.title } : {}),
    ...(data.dueDate !== undefined ? { dueDate: data.dueDate } : {}),
    ...(data.workPackageIds !== undefined ? { workPackageIds: data.workPackageIds, packagesTotal: data.workPackageIds.length } : {}),
    ...(data.reached !== undefined ? { state: data.reached ? 'REACHED' : item.state === 'REACHED' ? 'OPEN' : item.state } : {}),
  };
}

/**
 * Ändert einen Meilenstein; die Raute springt sofort (optimistisch), scheitert der Aufruf, geht sie
 * zurück. `quiet`: ohne Erfolgsmeldung – beim Ziehen sieht man, dass es geklappt hat.
 */
export function useUpdateMilestone(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateGoalMilestoneRequest; quiet?: boolean }) =>
      api.put<GoalMilestone>(`/goals/${goalId}/milestones/${id}`, data),
    onMutate: ({ id, data }) => patchGoalDetail(qc, goalId, (detail) => ({
      ...detail,
      milestones: detail.milestones.map((item) => (item.id === id ? applyToMilestone(item, data) : item)),
    })),
    onSuccess: (_item, { quiet }) => {
      if (!quiet) toast.success(t.goals.milestones.saved);
    },
    onError: (err, _change, context) => {
      if (context?.previous) qc.setQueryData(goalKeys.detail(goalId), context.previous);
      toast.error(apiErrorMessage(err, t.goals.milestones.saveFailed));
    },
    onSettled: () => invalidatePlanning(qc),
  });
}

export function useDeleteMilestone(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/goals/${goalId}/milestones/${id}`),
    onSuccess: () => {
      invalidatePlanning(qc);
      toast.success(t.goals.milestones.deleted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.milestones.saveFailed)),
  });
}
