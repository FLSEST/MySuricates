import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage, apiErrorStatus } from '../api/client';
import { defaultQueryRetry } from '../api/queryDefaults';
import { useToast } from './useToast';
import { useT } from '../i18n';
import { useModuleEnabled } from './useModules';
import type {
  CreateGoalRequest, DecisionState, Goal, GoalDetail, GoalGuard, GoalMilestone, GoalProject, GoalRelations, GoalStatus, GoalTodoItem,
  GoalWorkPackage, MilestoneState, PageResponse, Todo, UpdateGoalRequest, UpdateTodoPlanningRequest,
} from '../types';

/** Goals (TODO dc0566f8): collect, order and assess TODOs across projects. */
export const goalKeys = {
  all: ['goals'] as const,
  list: (statuses?: GoalStatus[], projectId?: string) =>
    ['goals', 'list', statuses?.join(',') ?? 'default', projectId ?? 'all'] as const,
  detail: (id: string) => ['goals', 'detail', id] as const,
  relations: (id: string, include: string[]) => ['goals', 'relations', id, include.join(',')] as const,
};

/**
 * Without a filter the server returns active and completed goals, newest first. `projectId` leaves only
 * goals to which this project is assigned (c60bf914); an older backend does not know the filter
 * and returns all – those then do not know their projects either and stay.
 */
export function useGoals(statuses?: GoalStatus[], projectId?: string) {
  const params = [
    ...(statuses ?? []).map((s) => `status=${s}`),
    ...(projectId ? [`projectId=${encodeURIComponent(projectId)}`] : []),
  ];
  const query = params.length ? `?${params.join('&')}` : '';
  const goalsModule = useModuleEnabled('goals');
  return useQuery({
    queryKey: goalKeys.list(statuses, projectId),
    enabled: goalsModule,
    queryFn: () => api.get<Goal[]>(`/goals${query}`)
      .then((goals) => (projectId ? goals.filter((goal) => !goal.projects || goal.projects.some((p) => p.id === projectId)) : goals)),
    staleTime: 30_000,
  });
}

const NO_GUARD: GoalGuard = { enabled: false, workflowId: null, scheduleId: null, nextRunAt: null };

const asksQuestion = (todo: GoalTodoItem) => todo.status !== 'ARCHIVED' && todo.status !== 'DONE' && !!todo.openQuestion;
/** Decided counts as in the backend: not archived, state DECIDED (68a5e24e). */
const isDecided = (todo: GoalTodoItem) => todo.status !== 'ARCHIVED' && todo.decisionState === 'DECIDED';

type DecisionFields = 'decision' | 'decidedAt' | 'decidedBy' | 'decisionState';

/**
 * A TODO as an older backend also delivers it: the work package still as `milestoneId`,
 * due date missing before 259f4f66, decisions before 68a5e24e.
 */
type RawTodo = Omit<GoalTodoItem, 'workPackageId' | 'dueDate' | DecisionFields> & Partial<Pick<GoalTodoItem, DecisionFields>> & {
  workPackageId?: string | null;
  milestoneId?: string | null;
  dueDate?: string | null;
};

/** State of the clarification when the backend does not send it: an open question is QUESTION. */
function decisionStateOf(todo: RawTodo): DecisionState {
  if (todo.decisionState) return todo.decisionState;
  if (todo.openQuestion) return 'QUESTION';
  return todo.decision ? 'DECIDED' : 'NONE';
}

/**
 * A work package as a backend before c60bf914 delivers it as "milestone": only a due date,
 * project, position and open questions only from stage 1.
 */
interface LegacyPackage {
  id: string;
  goalId: string;
  title: string;
  dueDate?: string | null;
  state: MilestoneState;
  total: number;
  done: number;
  reachedAt?: string | null;
  reopened?: boolean;
  projectId?: string | null;
  position?: number;
  openQuestions?: number;
  createdAt: string;
  updatedAt: string;
}

/** The answer of `GET /goals/{id}` – current or from an older backend. */
type RawGoalDetail = Omit<GoalDetail, 'todos' | 'projects' | 'workPackages' | 'milestones' | 'legacyBackend' | 'goal'> & {
  goal: Omit<Goal, 'guard'> & { guard?: GoalGuard };
  todos: RawTodo[];
  projects?: (Omit<GoalProject, 'decided'> & { decided?: number })[];
  workPackages?: (Omit<GoalWorkPackage, 'startDate' | 'endDate' | 'completedAt' | 'reopened' | 'openQuestions' | 'decided'>
    & Partial<Pick<GoalWorkPackage, 'startDate' | 'endDate' | 'completedAt' | 'reopened' | 'openQuestions' | 'decided'>>)[];
  milestones?: unknown[];
};

/**
 * Projects of an older backend: those from `byProject` (the projects of the TODOs), in its order.
 * "Ohne Projekt" is not a project of the goal.
 */
function projectsFromByProject(detail: RawGoalDetail, todos: GoalTodoItem[]): GoalProject[] {
  return detail.byProject.flatMap((entry, position) => (entry.projectId === null ? [] : [{
    projectId: entry.projectId,
    name: entry.projectName ?? entry.projectId,
    archived: false,
    position,
    total: entry.total,
    done: entry.done,
    openQuestions: todos.filter((todo) => todo.projectId === entry.projectId && asksQuestion(todo)).length,
    decided: todos.filter((todo) => todo.projectId === entry.projectId && isDecided(todo)).length,
  }]));
}

/** Project of a package without `projectId`: the one with most of its TODOs, otherwise the only one of the goal – like V92. */
function inferredProject(packageId: string, todos: GoalTodoItem[], projects: GoalProject[]): string | null {
  const counts = new Map<string, number>();
  todos.filter((todo) => todo.workPackageId === packageId && todo.projectId)
    .forEach((todo) => counts.set(todo.projectId!, (counts.get(todo.projectId!) ?? 0) + 1));
  const [busiest] = [...counts.entries()].sort((a, b) => b[1] - a[1]);
  return busiest?.[0] ?? (projects.length === 1 ? projects[0]!.projectId : null);
}

/** The "milestones" of an older backend are work packages with only one due date: a one-day package. */
function packagesFromLegacy(legacy: LegacyPackage[], todos: GoalTodoItem[], projects: GoalProject[]): GoalWorkPackage[] {
  return legacy.map((item, index) => ({
    id: item.id,
    goalId: item.goalId,
    title: item.title,
    projectId: item.projectId !== undefined ? item.projectId : inferredProject(item.id, todos, projects),
    startDate: item.dueDate ?? null,
    endDate: item.dueDate ?? null,
    position: item.position ?? index,
    state: item.state,
    total: item.total,
    done: item.done,
    openQuestions: item.openQuestions ?? todos.filter((todo) => todo.workPackageId === item.id && asksQuestion(todo)).length,
    decided: todos.filter((todo) => todo.workPackageId === item.id && isDecided(todo)).length,
    completedAt: item.reachedAt ?? null,
    reopened: item.reopened ?? false,
    createdAt: item.createdAt,
    updatedAt: item.updatedAt,
  }));
}

/**
 * The goal page copes with every backend state (c60bf914 goes live with the merge, the backend
 * only after a restart). If `workPackages` is missing, the backend is older: its "milestones" are
 * work packages (only `dueDate` → start = end), the TODOs point to them via `milestoneId`, real
 * milestones do not exist yet (`legacyBackend`). Before TODO 259f4f66 guard,
 * packages and due dates are also missing, before stage 1 the goal's projects – whatever can be derived
 * is derived, instead of failing on a missing field.
 */
export function normalizeGoalDetail(raw: GoalDetail | RawGoalDetail): GoalDetail {
  const detail = raw as RawGoalDetail;
  const legacyBackend = detail.workPackages === undefined;
  const decisionsSupported = detail.goal.progress.decided !== undefined;
  const todos = detail.todos.map(({ milestoneId, workPackageId, ...todo }): GoalTodoItem => ({
    ...todo,
    dueDate: todo.dueDate ?? null,
    workPackageId: workPackageId !== undefined ? workPackageId : (milestoneId ?? null),
    decision: todo.decision ?? null,
    decidedAt: todo.decidedAt ?? null,
    decidedBy: todo.decidedBy ?? null,
    decisionState: decisionStateOf(todo),
  }));
  const projects = detail.projects
    ? detail.projects.map((project): GoalProject => ({
      ...project,
      decided: project.decided ?? todos.filter((todo) => todo.projectId === project.projectId && isDecided(todo)).length,
    }))
    : projectsFromByProject(detail, todos);
  const workPackages = legacyBackend
    ? packagesFromLegacy((detail.milestones ?? []) as LegacyPackage[], todos, projects)
    : detail.workPackages!.map((item): GoalWorkPackage => ({
      ...item,
      projectId: item.projectId ?? null,
      startDate: item.startDate ?? null,
      endDate: item.endDate ?? null,
      openQuestions: item.openQuestions ?? 0,
      decided: item.decided ?? todos.filter((todo) => todo.workPackageId === item.id && isDecided(todo)).length,
      completedAt: item.completedAt ?? null,
      reopened: item.reopened ?? false,
    }));
  const milestones = legacyBackend ? [] : ((detail.milestones ?? []) as GoalMilestone[]).map((milestone): GoalMilestone => ({
    ...milestone,
    workPackageIds: milestone.workPackageIds ?? [],
    packagesTotal: milestone.packagesTotal ?? milestone.workPackageIds?.length ?? 0,
    packagesDone: milestone.packagesDone ?? 0,
    reachedAt: milestone.reachedAt ?? null,
    reopened: milestone.reopened ?? false,
  }));
  return {
    ...detail,
    goal: {
      ...detail.goal,
      guard: detail.goal.guard ?? NO_GUARD,
      progress: { ...detail.goal.progress, decided: detail.goal.progress.decided ?? todos.filter(isDecided).length },
    },
    todos, projects, workPackages, milestones, legacyBackend, decisionsSupported,
  };
}

export function useGoal(id: string | undefined) {
  const goalsModule = useModuleEnabled('goals');
  return useQuery({
    queryKey: goalKeys.detail(id ?? ''),
    queryFn: () => api.get<GoalDetail>(`/goals/${id}`).then(normalizeGoalDetail),
    enabled: goalsModule && !!id,
  });
}

/**
 * A 404 on `/relations` means: the backend is older than the map (TODO 49080447). That stays so
 * until the restart – do not retry, the goal page then shows a hint instead of an error.
 */
export function goalRelationsRetry(failureCount: number, error: unknown): boolean {
  return apiErrorStatus(error) !== 404 && defaultQueryRetry(failureCount, error);
}

/**
 * Relation map (TODO c6ea8f5c): per TODO origin, processing and result. `include` are the
 * candidates currently shown; they come into the map dashed. When the candidates change
 * the old map stays until the new one is there.
 */
export function useGoalRelations(id: string | undefined, include: string[], enabled = true) {
  const goalsModule = useModuleEnabled('goals');
  const query = include.length ? `?include=${include.map(encodeURIComponent).join(',')}` : '';
  return useQuery({
    queryKey: goalKeys.relations(id ?? '', include),
    queryFn: () => api.get<GoalRelations>(`/goals/${id}/relations${query}`),
    enabled: goalsModule && !!id && enabled,
    placeholderData: keepPreviousData,
    staleTime: 15_000,
    retry: (failureCount, error: Error) => goalRelationsRetry(failureCount, error),
  });
}

/** Statuses in which a TODO can still fit a goal. */
const CANDIDATE_STATUSES = ['OPEN', 'IN_PROGRESS', 'BACKLOG'];
/** At most this many candidates – exactly this many the relation map takes along. */
export const CANDIDATE_LIMIT = 20;

/** One page of candidates; `scoped` says whether the server can filter by the goal's projects. */
export type CandidatePage = PageResponse<Todo> & { scoped: boolean };

/**
 * Candidates (TODO c6ea8f5c): open TODOs without a goal, filtered by tag and search. Since c60bf914
 * only from the goal's projects (`/goals/{id}/candidates`), `allProjects` searches everywhere. An
 * older backend does not know the endpoint (404) – then as before via `/todos?goalId=none`, and
 * the page says `scoped: false`, so the switch does not promise something nobody fulfills.
 */
export function useGoalCandidates(goalId: string | undefined, filter: { q: string; tag: string }, allProjects: boolean, enabled = true) {
  const goalsModule = useModuleEnabled('goals');
  const q = filter.q.trim();
  const tag = filter.tag.trim();
  return useQuery({
    queryKey: ['todos', 'goal-candidates', goalId ?? '', q, tag, allProjects],
    queryFn: async (): Promise<CandidatePage> => {
      const scoped = new URLSearchParams({ page: '0', size: String(CANDIDATE_LIMIT), allProjects: String(allProjects) });
      if (q) scoped.set('q', q);
      if (tag) scoped.set('tag', tag);
      try {
        return { ...(await api.get<PageResponse<Todo>>(`/goals/${goalId}/candidates?${scoped.toString()}`)), scoped: true };
      } catch (err) {
        if (apiErrorStatus(err) !== 404) throw err;
      }
      const legacy = new URLSearchParams({ page: '0', size: String(CANDIDATE_LIMIT), goalId: 'none' });
      CANDIDATE_STATUSES.forEach((status) => legacy.append('status', status));
      if (tag) legacy.set('tag', tag);
      if (q) legacy.set('q', q);
      return { ...(await api.get<PageResponse<Todo>>(`/todos?${legacy.toString()}`)), scoped: false };
    },
    enabled: goalsModule && !!goalId && enabled,
    placeholderData: keepPreviousData,
    staleTime: 60_000,
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

/** New order: the named TODOs get rank 1..n, all others of the goal none. */
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

/** Goal, rank and assessment of a TODO; without `ratedBy` the change counts as human. */
export function useUpdateTodoPlanning() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateTodoPlanningRequest }) =>
      api.patch<Todo>(`/todos/${id}/planning`, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: goalKeys.all });
      qc.invalidateQueries({ queryKey: ['todos'] });
    },
    // No toasts of its own: saving happens with the TODO dialog, which reports success and failure (bbee09e6)
  });
}
