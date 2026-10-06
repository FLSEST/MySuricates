import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalKeys } from './useGoals';
import { invalidatePlanning, patchGoalDetail } from './useGoalTimeline';
import type { CreateGoalMilestoneRequest, GoalMilestone, UpdateGoalMilestoneRequest } from '../types';

/**
 * Milestones of a goal (c60bf914): dates for the whole goal, optionally linked to work packages –
 * reached when the linked packages are completed, without a link by hand.
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

/** The change in the stored milestone – date and links visible immediately. */
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
 * Changes a milestone; the diamond jumps immediately (optimistic), if the call fails, it goes
 * back. `quiet`: without a success message – when dragging you can see that it worked.
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
