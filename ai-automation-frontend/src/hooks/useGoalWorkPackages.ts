import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalKeys, normalizeGoalDetail } from './useGoals';
import { invalidatePlanning, patchGoalDetail } from './useGoalTimeline';
import type { CreateGoalWorkPackageRequest, GoalDetail, GoalWorkPackage, UpdateGoalWorkPackageRequest } from '../types';

/**
 * Arbeitspakete eines Ziels (c60bf914): je ein Projekt, TODOs darunter, optional ein Zeitraum
 * (`startDate`/`endDate`, beide oder keins) – als Balken auf dem Zeitstrahl.
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

/** Die Änderung im gespeicherten Paket – `''` leert ein Datum, wie beim Server. */
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
 * Ändert ein Paket. Der Balken springt sofort an die neue Stelle (optimistisch); scheitert der
 * Aufruf, geht er zurück. `quiet`: ohne Erfolgsmeldung – beim Ziehen sieht man, dass es geklappt hat.
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

/** Die TODOs bleiben im Ziel, nur ohne Paket; Meilensteine verlieren die Verknüpfung. */
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
 * Neue Reihenfolge der Pakete eines Projekts (`ids`: alle Pakete genau eines Projekts). Die Antwort
 * sind alle Pakete des Ziels; sie ersetzen die im Detail gleich, der Rest lädt nach.
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
