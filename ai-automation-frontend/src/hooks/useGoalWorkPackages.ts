import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalKeys, normalizeGoalDetail } from './useGoals';
import { invalidatePlanning, patchGoalDetail } from './useGoalTimeline';
import type { CreateGoalWorkPackageRequest, GoalDetail, GoalWorkPackage, UpdateGoalWorkPackageRequest } from '../types';

/**
 * Work packages of a goal (c60bf914): one project each, TODOs below, optionally a period
 * (`startDate`/`endDate`, both or neither) – as a bar on the timeline.
 */
export function useCreateWorkPackage(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: CreateGoalWorkPackageRequest) => api.post<GoalWorkPackage>(`/goals/${goalId}/work-packages`, data),
    onSuccess: () => {
      invalidatePlanning(qc);
      toast.success(t.goals.workPackages.saved);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.workPackages.saveFailed)),
  });
}

/** The change in the stored package – `''` clears a date, as on the server. */
function applyToPackage(item: GoalWorkPackage, data: UpdateGoalWorkPackageRequest): GoalWorkPackage {
  return {
    ...item,
    ...(data.title !== undefined ? { title: data.title } : {}),
    ...(data.projectId !== undefined ? { projectId: data.projectId || null } : {}),
    ...(data.startDate !== undefined ? { startDate: data.startDate || null } : {}),
    ...(data.endDate !== undefined ? { endDate: data.endDate || null } : {}),
  };
}

/**
 * Changes a package. The bar jumps immediately to the new place (optimistic); if the
 * call fails, it goes back. `quiet`: without a success message – when dragging you can see that it worked.
 */
export function useUpdateWorkPackage(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateGoalWorkPackageRequest; quiet?: boolean }) =>
      api.put<GoalWorkPackage>(`/goals/${goalId}/work-packages/${id}`, data),
    onMutate: ({ id, data }) => patchGoalDetail(qc, goalId, (detail) => ({
      ...detail,
      workPackages: detail.workPackages.map((item) => (item.id === id ? applyToPackage(item, data) : item)),
    })),
    onSuccess: (_item, { quiet }) => {
      if (!quiet) toast.success(t.goals.workPackages.saved);
    },
    onError: (err, _change, context) => {
      if (context?.previous) qc.setQueryData(goalKeys.detail(goalId), context.previous);
      toast.error(apiErrorMessage(err, t.goals.workPackages.saveFailed));
    },
    onSettled: () => invalidatePlanning(qc),
  });
}

/** The TODOs stay in the goal, just without a package; milestones lose the link. */
export function useDeleteWorkPackage(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/goals/${goalId}/work-packages/${id}`),
    onSuccess: () => {
      invalidatePlanning(qc);
      toast.success(t.goals.workPackages.deleted);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.workPackages.saveFailed)),
  });
}

/**
 * New order of the packages of a project (`ids`: all packages of exactly one project). The answer
 * is all packages of the goal; they replace those in the detail directly, the rest reloads.
 */
export function useReorderWorkPackages(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (ids: string[]) => api.put<GoalWorkPackage[]>(`/goals/${goalId}/work-packages/order`, { ids }),
    onSuccess: (workPackages) => {
      const key = goalKeys.detail(goalId);
      const previous = qc.getQueryData<GoalDetail>(key);
      if (previous && Array.isArray(workPackages)) qc.setQueryData(key, normalizeGoalDetail({ ...previous, workPackages }));
      invalidatePlanning(qc);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.workPackages.saveFailed)),
  });
}
