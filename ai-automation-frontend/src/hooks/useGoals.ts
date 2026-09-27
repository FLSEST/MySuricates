import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage, apiErrorStatus } from '../api/client';
import { defaultQueryRetry } from '../api/queryDefaults';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { useTodos } from './useTodos';
import type {
  CreateGoalRequest, Goal, GoalDetail, GoalGuard, GoalMilestone, GoalProject, GoalRelations, GoalStatus, GoalTodoItem, Todo,
  UpdateGoalRequest, UpdateTodoPlanningRequest,
} from '../types';

/** Ziele (TODO dc0566f8): TODOs projektübergreifend sammeln, ordnen und einschätzen. */
export const goalKeys = {
  all: ['goals'] as const,
  list: (statuses?: GoalStatus[], projectId?: string) =>
    ['goals', 'list', statuses?.join(',') ?? 'default', projectId ?? 'all'] as const,
  detail: (id: string) => ['goals', 'detail', id] as const,
  relations: (id: string, include: string[]) => ['goals', 'relations', id, include.join(',')] as const,
};

/**
 * Ohne Filter liefert der Server aktive und erledigte Ziele, neueste zuerst. `projectId` lässt nur
 * Ziele übrig, denen dieses Projekt zugeordnet ist (c60bf914); ein älteres Backend kennt den Filter
 * nicht und liefert alle – die kennen dann auch ihre Projekte nicht und bleiben stehen.
 */
export function useGoals(statuses?: GoalStatus[], projectId?: string) {
  const params = [
    ...(statuses ?? []).map((s) => `status=${s}`),
    ...(projectId ? [`projectId=${encodeURIComponent(projectId)}`] : []),
  ];
  const query = params.length ? `?${params.join('&')}` : '';
  return useQuery({
    queryKey: goalKeys.list(statuses, projectId),
    queryFn: () => api.get<Goal[]>(`/goals${query}`)
      .then((goals) => (projectId ? goals.filter((goal) => !goal.projects || goal.projects.some((p) => p.id === projectId)) : goals)),
    staleTime: 30_000,
  });
}

const NO_GUARD: GoalGuard = { enabled: false, workflowId: null, scheduleId: null, nextRunAt: null };

const counted = (todo: GoalTodoItem) => todo.status !== 'ARCHIVED';
const asksQuestion = (todo: GoalTodoItem) => counted(todo) && todo.status !== 'DONE' && !!todo.openQuestion;

/** Die Antwort eines Backends vor c60bf914: ohne `projects`, Teilziele ohne `projectId`/`position`/`openQuestions`. */
type OlderGoalDetail = Omit<GoalDetail, 'projects' | 'milestones'> & {
  projects?: GoalProject[];
  milestones?: (Omit<GoalMilestone, 'projectId' | 'position' | 'openQuestions'>
    & Partial<Pick<GoalMilestone, 'projectId' | 'position' | 'openQuestions'>>)[];
};

/**
 * Projekte eines älteren Backends: die aus `byProject` (die Projekte der TODOs), in dessen Reihenfolge.
 * „Ohne Projekt“ ist kein Projekt des Ziels.
 */
function projectsFromByProject(detail: OlderGoalDetail, todos: GoalTodoItem[]): GoalProject[] {
  return detail.byProject.flatMap((entry, position) => (entry.projectId === null ? [] : [{
    projectId: entry.projectId,
    name: entry.projectName ?? entry.projectId,
    archived: false,
    position,
    total: entry.total,
    done: entry.done,
    openQuestions: todos.filter((todo) => todo.projectId === entry.projectId && asksQuestion(todo)).length,
  }]));
}

/** Projekt eines Teilziels ohne `projectId`: das mit den meisten seiner TODOs, sonst das einzige des Ziels – wie V92. */
function inferredProject(milestoneId: string, todos: GoalTodoItem[], projects: GoalProject[]): string | null {
  const counts = new Map<string, number>();
  todos.filter((todo) => todo.milestoneId === milestoneId && todo.projectId)
    .forEach((todo) => counts.set(todo.projectId!, (counts.get(todo.projectId!) ?? 0) + 1));
  const [busiest] = [...counts.entries()].sort((a, b) => b[1] - a[1]);
  return busiest?.[0] ?? (projects.length === 1 ? projects[0]!.projectId : null);
}

/**
 * Ein Backend vor TODO 259f4f66 kennt Wächter, Teilziele und Fälligkeiten im Ziel noch nicht, eins
 * vor c60bf914 weder die Projekte des Ziels noch Projekt, Position und offene Fragen der Teilziele –
 * bis zu seinem Neustart leitet die Zielseite ab, was sich ableiten lässt, statt an einem fehlenden
 * Feld zu scheitern.
 */
export function normalizeGoalDetail(raw: GoalDetail | OlderGoalDetail): GoalDetail {
  const detail = raw as OlderGoalDetail;
  const todos = detail.todos.map((todo) => ({ ...todo, dueDate: todo.dueDate ?? null, milestoneId: todo.milestoneId ?? null }));
  const projects = detail.projects ?? projectsFromByProject(detail, todos);
  const milestones = (detail.milestones ?? []).map((milestone, index): GoalMilestone => ({
    ...milestone,
    dueDate: milestone.dueDate ?? null,
    projectId: milestone.projectId !== undefined ? milestone.projectId : inferredProject(milestone.id, todos, projects),
    position: milestone.position ?? index,
    openQuestions: milestone.openQuestions
      ?? todos.filter((todo) => todo.milestoneId === milestone.id && asksQuestion(todo)).length,
  }));
  return { ...detail, goal: { ...detail.goal, guard: detail.goal.guard ?? NO_GUARD }, todos, projects, milestones };
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
