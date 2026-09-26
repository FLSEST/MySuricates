import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage, apiErrorStatus } from '../api/client';
import { defaultQueryRetry } from '../api/queryDefaults';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { useTodos } from './useTodos';
import type {
  CreateGoalRequest, Goal, GoalDetail, GoalGuard, GoalRelations, GoalStatus, Todo, UpdateGoalRequest,
  UpdateTodoPlanningRequest,
} from '../types';

/** Ziele (TODO dc0566f8): TODOs projektübergreifend sammeln, ordnen und einschätzen. */
export const goalKeys = {
  all: ['goals'] as const,
  list: (statuses?: GoalStatus[]) => ['goals', 'list', statuses?.join(',') ?? 'default'] as const,
  detail: (id: string) => ['goals', 'detail', id] as const,
  relations: (id: string, include: string[]) => ['goals', 'relations', id, include.join(',')] as const,
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

const NO_GUARD: GoalGuard = { enabled: false, workflowId: null, scheduleId: null, nextRunAt: null };

/**
 * Ein Backend vor TODO 259f4f66 kennt Wächter, Meilensteine und Fälligkeiten im Ziel noch nicht –
 * bis zu seinem Neustart zeigt die Zielseite sie dann leer, statt an einem fehlenden Feld zu scheitern.
 */
export function normalizeGoalDetail(detail: GoalDetail): GoalDetail {
  return {
    ...detail,
    goal: { ...detail.goal, guard: detail.goal.guard ?? NO_GUARD },
    milestones: detail.milestones ?? [],
    todos: detail.todos.map((todo) => ({ ...todo, dueDate: todo.dueDate ?? null, milestoneId: todo.milestoneId ?? null })),
  };
}

export function useGoal(id: string | undefined) {
  return useQuery({
    queryKey: goalKeys.detail(id ?? ''),
    queryFn: () => api.get<GoalDetail>(`/goals/${id}`).then(normalizeGoalDetail),
    enabled: !!id,
  });
}

/**
 * Ein 404 auf `/relations` heißt: Das Backend ist älter als die Karte (TODO 49080447). Das bleibt so
 * bis zum Neustart – nicht wiederholen, die Zielseite zeigt dann einen Hinweis statt eines Fehlers.
 */
export function goalRelationsRetry(failureCount: number, error: unknown): boolean {
  return apiErrorStatus(error) !== 404 && defaultQueryRetry(failureCount, error);
}

/**
 * Relationskarte (TODO c6ea8f5c): je TODO Herkunft, Bearbeitung und Ergebnis. `include` sind die
 * gerade gezeigten Kandidaten; sie kommen gestrichelt mit in die Karte. Beim Wechsel der Kandidaten
 * bleibt die alte Karte stehen, bis die neue da ist.
 */
export function useGoalRelations(id: string | undefined, include: string[], enabled = true) {
  const query = include.length ? `?include=${include.map(encodeURIComponent).join(',')}` : '';
  return useQuery({
    queryKey: goalKeys.relations(id ?? '', include),
    queryFn: () => api.get<GoalRelations>(`/goals/${id}/relations${query}`),
    enabled: !!id && enabled,
    placeholderData: keepPreviousData,
    staleTime: 15_000,
    retry: (failureCount, error: Error) => goalRelationsRetry(failureCount, error),
  });
}

/** Status, in denen ein TODO noch zu einem Ziel passen kann. */
const CANDIDATE_STATUSES = ['OPEN', 'IN_PROGRESS', 'BACKLOG'];
/** Höchstens so viele Kandidaten – genau so viele nimmt die Relationskarte mit. */
export const CANDIDATE_LIMIT = 20;

/** Kandidaten (TODO c6ea8f5c): offene TODOs ohne Ziel (`goalId=none`), gefiltert nach Tag und Suche. */
export function useGoalCandidates(filter: { q: string; tag: string }, enabled = true) {
  return useTodos(
    { status: CANDIDATE_STATUSES, goalId: 'none', tag: filter.tag.trim(), q: filter.q.trim() },
    0, CANDIDATE_LIMIT, enabled,
  );
}

/**
 * Ordnet TODOs einem Ziel zu – ohne Rang, sie stehen hinter den eingeordneten. Der Kandidat
 * verschwindet dabei sofort aus der Liste; gegen Fehlklicks bietet der Toast „Rückgängig“
 * (TODO c9fcccc3). Das läuft auch, wenn die Zielseite schon verlassen ist: nur Client und Store.
 */
export function useAssignGoalTodos() {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  const refresh = () => {
    qc.invalidateQueries({ queryKey: goalKeys.all });
    qc.invalidateQueries({ queryKey: ['todos'] });
  };
  const undo = (todoIds: string[]) => {
    // `goalId: ''` löst die Zuordnung (mit Rang und Meilenstein); null hieße „unverändert“
    void Promise.all(todoIds.map((todoId) => api.patch<Todo>(`/todos/${todoId}/planning`, { goalId: '' })))
      .then(() => toast.success(t.goals.assignUndone))
      .catch((err) => toast.error(apiErrorMessage(err, t.goals.saveFailed)))
      .finally(refresh);
  };
  return useMutation({
    mutationFn: ({ id, todoIds }: { id: string; todoIds: string[] }) =>
      api.post<GoalDetail>(`/goals/${id}/todos`, { todoIds }),
    onSuccess: (detail, { todoIds }) => {
      qc.setQueryData(goalKeys.detail(detail.goal.id), normalizeGoalDetail(detail));
      refresh();
      toast.success(t.goals.assigned, { label: t.goals.undoAssign, onClick: () => undo(todoIds) });
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.goals.saveFailed)),
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
      qc.setQueryData(goalKeys.detail(detail.goal.id), normalizeGoalDetail(detail));
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
