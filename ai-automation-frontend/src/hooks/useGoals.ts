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
/** Entschieden zählt wie beim Backend: nicht archiviert, Zustand DECIDED (68a5e24e). */
const isDecided = (todo: GoalTodoItem) => todo.status !== 'ARCHIVED' && todo.decisionState === 'DECIDED';

type DecisionFields = 'decision' | 'decidedAt' | 'decidedBy' | 'decisionState';

/**
 * Ein TODO, wie es auch ein älteres Backend liefert: das Arbeitspaket noch als `milestoneId`,
 * Fälligkeit fehlt vor 259f4f66, Entscheidungen vor 68a5e24e.
 */
type RawTodo = Omit<GoalTodoItem, 'workPackageId' | 'dueDate' | DecisionFields> & Partial<Pick<GoalTodoItem, DecisionFields>> & {
  workPackageId?: string | null;
  milestoneId?: string | null;
  dueDate?: string | null;
};

/** Zustand der Klärung, wenn das Backend ihn nicht mitschickt: eine offene Frage ist QUESTION. */
function decisionStateOf(todo: RawTodo): DecisionState {
  if (todo.decisionState) return todo.decisionState;
  if (todo.openQuestion) return 'QUESTION';
  return todo.decision ? 'DECIDED' : 'NONE';
}

/**
 * Ein Arbeitspaket, wie es ein Backend vor c60bf914 als „milestone“ liefert: nur eine Fälligkeit,
 * Projekt, Position und offene Fragen erst ab Stufe 1.
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

/** Die Antwort von `GET /goals/{id}` – aktuell oder von einem älteren Backend. */
type RawGoalDetail = Omit<GoalDetail, 'todos' | 'projects' | 'workPackages' | 'milestones' | 'legacyBackend' | 'goal'> & {
  goal: Omit<Goal, 'guard'> & { guard?: GoalGuard };
  todos: RawTodo[];
  projects?: (Omit<GoalProject, 'decided'> & { decided?: number })[];
  workPackages?: (Omit<GoalWorkPackage, 'startDate' | 'endDate' | 'completedAt' | 'reopened' | 'openQuestions' | 'decided'>
    & Partial<Pick<GoalWorkPackage, 'startDate' | 'endDate' | 'completedAt' | 'reopened' | 'openQuestions' | 'decided'>>)[];
  milestones?: unknown[];
};

/**
 * Projekte eines älteren Backends: die aus `byProject` (die Projekte der TODOs), in dessen Reihenfolge.
 * „Ohne Projekt“ ist kein Projekt des Ziels.
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

/** Projekt eines Pakets ohne `projectId`: das mit den meisten seiner TODOs, sonst das einzige des Ziels – wie V92. */
function inferredProject(packageId: string, todos: GoalTodoItem[], projects: GoalProject[]): string | null {
  const counts = new Map<string, number>();
  todos.filter((todo) => todo.workPackageId === packageId && todo.projectId)
    .forEach((todo) => counts.set(todo.projectId!, (counts.get(todo.projectId!) ?? 0) + 1));
  const [busiest] = [...counts.entries()].sort((a, b) => b[1] - a[1]);
  return busiest?.[0] ?? (projects.length === 1 ? projects[0]!.projectId : null);
}

/** Die „milestones“ eines älteren Backends sind Arbeitspakete mit nur einer Fälligkeit: ein Ein-Tages-Paket. */
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
 * Die Zielseite kommt mit jedem Backend-Stand zurecht (c60bf914 geht mit dem Merge live, das Backend
 * erst nach einem Neustart). Fehlt `workPackages`, ist das Backend älter: Seine „milestones“ sind
 * Arbeitspakete (nur `dueDate` → Beginn = Ende), die TODOs zeigen per `milestoneId` darauf, echte
 * Meilensteine gibt es noch nicht (`legacyBackend`). Vor TODO 259f4f66 fehlen außerdem Wächter,
 * Pakete und Fälligkeiten, vor Stufe 1 die Projekte des Ziels – abgeleitet wird, was sich ableiten
 * lässt, statt an einem fehlenden Feld zu scheitern.
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

/** Status, in denen ein TODO noch zu einem Ziel passen kann. */
const CANDIDATE_STATUSES = ['OPEN', 'IN_PROGRESS', 'BACKLOG'];
/** Höchstens so viele Kandidaten – genau so viele nimmt die Relationskarte mit. */
export const CANDIDATE_LIMIT = 20;

/** Eine Seite Kandidaten; `scoped` sagt, ob der Server nach den Projekten des Ziels filtern kann. */
export type CandidatePage = PageResponse<Todo> & { scoped: boolean };

/**
 * Kandidaten (TODO c6ea8f5c): offene TODOs ohne Ziel, gefiltert nach Tag und Suche. Seit c60bf914
 * nur aus den Projekten des Ziels (`/goals/{id}/candidates`), `allProjects` sucht überall. Ein
 * älteres Backend kennt den Endpunkt nicht (404) – dann wie früher über `/todos?goalId=none`, und
 * die Seite sagt `scoped: false`, damit der Schalter nicht etwas verspricht, das keiner einlöst.
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
