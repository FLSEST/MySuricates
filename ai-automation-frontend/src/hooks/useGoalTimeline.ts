import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { goalKeys, normalizeGoalDetail } from './useGoals';
import { applyPlan, type PlanChanges } from '../components/goal/timelineLayout';
import type { CreateGoalMilestoneRequest, GoalDetail, GoalMilestone, UpdateGoalMilestoneRequest } from '../types';

/**
 * Zeitstrahl eines Ziels (TODO e192df18): TODOs einplanen und Meilensteine pflegen. Danach ist
 * neu zu laden, was davon abhängt – Ziele (Fortschritt, Meilenstein-Zustand) und TODO-Listen samt
 * Cockpit („Braucht dich“).
 */
function invalidatePlanning(qc: QueryClient) {
  qc.invalidateQueries({ queryKey: goalKeys.all });
  qc.invalidateQueries({ queryKey: ['todos'] });
}

export interface PlanGoalTodo {
  todoId: string;
  changes: PlanChanges;
  /** Aus dem Menü eines Chips: mit Bestätigung, denn dort sieht man den Chip nicht springen. */
  announce?: boolean;
}

/**
 * Fälligkeit (`PUT /todos/{id}`) und Meilenstein (`PATCH …/planning`) eines TODOs. Der Chip springt
 * sofort an die neue Stelle; scheitert ein Aufruf, geht er zurück.
 */
export function usePlanGoalTodo(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: async ({ todoId, changes }: PlanGoalTodo) => {
      if (changes.dueDate !== undefined) await api.put(`/todos/${todoId}`, { dueDate: changes.dueDate });
      if (changes.milestoneId !== undefined) await api.patch(`/todos/${todoId}/planning`, { milestoneId: changes.milestoneId });
    },
    onMutate: async ({ todoId, changes }) => {
      const key = goalKeys.detail(goalId);
      await qc.cancelQueries({ queryKey: key });
      const previous = qc.getQueryData<GoalDetail>(key);
      if (previous) qc.setQueryData<GoalDetail>(key, { ...previous, todos: applyPlan(previous.todos, todoId, changes) });
      return { previous };
    },
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

/** Legt ein Teilziel an – mit Projekt, das Datum ist optional (c60bf914). */
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

export function useUpdateMilestone(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateGoalMilestoneRequest }) =>
      api.put<GoalMilestone>(`/goals/${goalId}/milestones/${id}`, data),
    onSuccess: () => {
      invalidatePlanning(qc);
      toast.success(t.goals.milestones.saved);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.milestones.saveFailed)),
  });
}

/**
 * Datum eines Teilziels beim Ziehen (c60bf914): `dueDate` als ISO-Zeitpunkt, `''` entfernt es. Die
 * Raute springt sofort; scheitert der Aufruf, geht sie zurück. Keine Erfolgsmeldung – die Raute
 * steht ja schon da, der Screenreader hat das Ablegen angesagt.
 */
export function useMoveMilestone(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: ({ id, dueDate }: { id: string; dueDate: string }) =>
      api.put<GoalMilestone>(`/goals/${goalId}/milestones/${id}`, { dueDate }),
    onMutate: async ({ id, dueDate }) => {
      const key = goalKeys.detail(goalId);
      await qc.cancelQueries({ queryKey: key });
      const previous = qc.getQueryData<GoalDetail>(key);
      if (previous) {
        qc.setQueryData<GoalDetail>(key, {
          ...previous,
          milestones: previous.milestones.map((m) => (m.id === id ? { ...m, dueDate: dueDate || null } : m)),
        });
      }
      return { previous };
    },
    onError: (err, _move, context) => {
      if (context?.previous) qc.setQueryData(goalKeys.detail(goalId), context.previous);
      toast.error(apiErrorMessage(err, t.goals.milestones.saveFailed));
    },
    onSettled: () => invalidatePlanning(qc),
  });
}

/**
 * Neue Reihenfolge der Teilziele eines Projekts (`ids`: alle Teilziele genau eines Projekts). Die
 * Antwort sind alle Teilziele des Ziels; sie ersetzen die im Detail gleich, der Rest lädt nach.
 */
export function useReorderMilestones(goalId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: (ids: string[]) => api.put<GoalMilestone[]>(`/goals/${goalId}/milestones/order`, { ids }),
    onSuccess: (milestones) => {
      const key = goalKeys.detail(goalId);
      const previous = qc.getQueryData<GoalDetail>(key);
      if (previous && Array.isArray(milestones)) qc.setQueryData(key, normalizeGoalDetail({ ...previous, milestones }));
      invalidatePlanning(qc);
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.milestones.saveFailed)),
  });
}

/** Die TODOs bleiben im Ziel, nur ohne Teilziel. */
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
