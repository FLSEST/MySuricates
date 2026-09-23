import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type {
  CreateGoalRequest, Goal, GoalDetail, GoalStatus, Todo, UpdateGoalRequest, UpdateTodoPlanningRequest,
} from '../types';

/** Ziele (TODO dc0566f8): TODOs projektübergreifend sammeln, ordnen und einschätzen. */
export const goalKeys = {
  all: ['goals'] as const,
  list: (statuses?: GoalStatus[]) => ['goals', 'list', statuses?.join(',') ?? 'default'] as const,
  detail: (id: string) => ['goals', 'detail', id] as const,
};

/** Ohne Filter liefert der Server aktive und erledigte Ziele, neueste zuerst. */
export function useGoals(statuses?: GoalStatus[]) {
  const query = statuses?.length ? `?${statuses.map((s) => `status=${s}`).join('&')}` : '';
  return useQuery({
    queryKey: goalKeys.list(statuses),
    queryFn: () => api.get<Goal[]>(`/goals${query}`),
    staleTime: 30_000,
  });
}

export function useGoal(id: string | undefined) {
  return useQuery({
    queryKey: goalKeys.detail(id ?? ''),
    queryFn: () => api.get<GoalDetail>(`/goals/${id}`),
    enabled: !!id,
  });
}

export function useCreateGoal() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (data: CreateGoalRequest) => api.post<Goal>('/goals', data),
    onSuccess: () => qc.invalidateQueries({ queryKey: goalKeys.all }),
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.saveFailed)),
  });
}

export function useUpdateGoal() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateGoalRequest }) => api.put<Goal>(`/goals/${id}`, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: goalKeys.all }),
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.saveFailed)),
  });
}

export function useDeleteGoal() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/goals/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: goalKeys.all });
      qc.invalidateQueries({ queryKey: ['todos'] });
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.saveFailed)),
  });
}

/** Neue Reihenfolge: die genannten TODOs bekommen Rang 1..n, alle übrigen des Ziels keinen. */
export function useReorderGoal() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, todoIds }: { id: string; todoIds: string[] }) =>
      api.put<GoalDetail>(`/goals/${id}/order`, { todoIds }),
    onSuccess: (detail) => {
      qc.setQueryData(goalKeys.detail(detail.goal.id), detail);
      qc.invalidateQueries({ queryKey: ['goals', 'list'] });
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.saveFailed)),
  });
}

/** Ziel, Rang und Einschätzung eines TODOs; ohne `ratedBy` gilt die Änderung als menschlich. */
export function useUpdateTodoPlanning() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateTodoPlanningRequest }) =>
      api.patch<Todo>(`/todos/${id}/planning`, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: goalKeys.all });
      qc.invalidateQueries({ queryKey: ['todos'] });
      toast.success(t.goals.planningSaved);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.saveFailed)),
  });
}
