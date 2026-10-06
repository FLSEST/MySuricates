import type { GoalDetail, GoalProject, GoalTodoItem, GoalWorkPackage } from '../../types';

/**
 * Outline of a goal (c60bf914), pure and testable: project → work package → TODOs, per project
 * plus „Ohne Arbeitspaket“, at the end „Ohne Projekt“. Each node counts its TODOs. The milestones
 * stand above it on their own (they belong to the whole goal, not to a project).
 *
 * Counted are non-archived TODOs; open questions only on TODOs that are not yet done,
 * decisions on all (68a5e24e, like the server).
 * Archived TODOs are not in the outline either.
 */

/** Key of the group „Ohne Projekt“ - project IDs are UUIDs, so this does not collide. */
export const NO_PROJECT = 'none';

export interface OutlineCounts {
  total: number;
  done: number;
  openQuestions: number;
  /** Entschiedene TODOs (68a5e24e). */
  decided: number;
}

export interface OutlineTodo {
  todo: GoalTodoItem;
  /** Legacy case (⚠): the TODO has a different project than its work package. It is read, not rejected. */
  mismatch: boolean;
}

export interface OutlinePackage {
  workPackage: GoalWorkPackage;
  todos: OutlineTodo[];
  counts: OutlineCounts;
}

export interface OutlineProject {
  /** Projekt-ID oder {@link NO_PROJECT}. */
  key: string;
  /** null for „Ohne Projekt“. */
  project: GoalProject | null;
  /** The project of a TODO does not (yet) belong to the goal - legacy data only, otherwise the server attaches it. */
  linked: boolean;
  /** In Reihenfolge ihrer Position. */
  packages: OutlinePackage[];
  /** TODOs of the project without a work package. */
  loose: OutlineTodo[];
  counts: OutlineCounts;
}

export interface GoalOutline {
  /** The projects of the goal by position, then projects that come in only via a TODO. */
  projects: OutlineProject[];
  /** „Ohne Projekt“ - only if there are TODOs or work packages there. */
  noProject: OutlineProject | null;
}

const shown = (todo: GoalTodoItem) => todo.status !== 'ARCHIVED';

export function countTodos(todos: GoalTodoItem[]): OutlineCounts {
  const counted = todos.filter(shown);
  return {
    total: counted.length,
    done: counted.filter((todo) => todo.status === 'DONE').length,
    openQuestions: counted.filter((todo) => todo.status !== 'DONE' && !!todo.openQuestion).length,
    decided: counted.filter((todo) => todo.decisionState === 'DECIDED').length,
  };
}

const sumCounts = (parts: OutlineCounts[]): OutlineCounts => parts.reduce(
  (sum, part) => ({
    total: sum.total + part.total,
    done: sum.done + part.done,
    openQuestions: sum.openQuestions + part.openQuestions,
    decided: sum.decided + part.decided,
  }),
  { total: 0, done: 0, openQuestions: 0, decided: 0 },
);

/** Anteil erledigt in Prozent, 0 ohne TODOs. */
export function percentDone(counts: OutlineCounts): number {
  return counts.total === 0 ? 0 : Math.round((counts.done / counts.total) * 100);
}

/** Packages by `position`; with equal position the server's order stays. */
function byPosition(workPackages: GoalWorkPackage[]): GoalWorkPackage[] {
  return workPackages.map((item, index) => ({ item, index }))
    .sort((a, b) => a.item.position - b.item.position || a.index - b.index)
    .map(({ item }) => item);
}

export function buildOutline(detail: Pick<GoalDetail, 'projects' | 'workPackages' | 'todos'>): GoalOutline {
  const projectKeys = new Set(detail.projects.map((project) => project.projectId));
  const packageById = new Map(detail.workPackages.map((item) => [item.id, item]));
  /** Group of a package: its project, otherwise (project deleted, legacy data) „Ohne Projekt“. */
  const groupOfPackage = (item: GoalWorkPackage) => item.projectId ?? NO_PROJECT;

  const todosBy = new Map<string, OutlineTodo[]>();
  const push = (key: string, entry: OutlineTodo) => todosBy.set(key, [...(todosBy.get(key) ?? []), entry]);
  const strays = new Map<string, string>();

  for (const todo of detail.todos.filter(shown)) {
    const item = todo.workPackageId ? packageById.get(todo.workPackageId) : undefined;
    if (item) {
      push(`wp:${item.id}`, { todo, mismatch: (todo.projectId ?? null) !== (item.projectId ?? null) });
      continue;
    }
    const key = todo.projectId ?? NO_PROJECT;
    if (todo.projectId && !projectKeys.has(todo.projectId)) strays.set(todo.projectId, todo.projectName ?? todo.projectId);
    push(`loose:${key}`, { todo, mismatch: false });
  }

  const group = (key: string, project: GoalProject | null, linked: boolean): OutlineProject => {
    const packages = byPosition(detail.workPackages.filter((item) => groupOfPackage(item) === key))
      .map((workPackage): OutlinePackage => {
        const todos = todosBy.get(`wp:${workPackage.id}`) ?? [];
        return { workPackage, todos, counts: countTodos(todos.map((entry) => entry.todo)) };
      });
    const loose = todosBy.get(`loose:${key}`) ?? [];
    const counts = sumCounts([...packages.map((entry) => entry.counts), countTodos(loose.map((entry) => entry.todo))]);
    return { key, project, linked, packages, loose, counts };
  };

  // Packages whose project no longer belongs to the goal get their project as a group of its own
  for (const item of detail.workPackages) {
    if (item.projectId && !projectKeys.has(item.projectId) && !strays.has(item.projectId)) {
      const named = detail.todos.find((todo) => todo.projectId === item.projectId)?.projectName;
      strays.set(item.projectId, named ?? item.projectId);
    }
  }

  const ordered = [...detail.projects].sort((a, b) => a.position - b.position);
  const projects = [
    ...ordered.map((project) => group(project.projectId, project, true)),
    ...[...strays.entries()].map(([projectId, name], index) => group(projectId, {
      projectId, name, archived: false, position: ordered.length + index, total: 0, done: 0, openQuestions: 0, decided: 0,
    }, false)),
  ];
  const rest = group(NO_PROJECT, null, true);
  const noProject = rest.packages.length > 0 || rest.loose.length > 0 ? rest : null;
  return { projects, noProject };
}

/**
 * New order after ↑/↓: the element at `index` swaps with its neighbour. null if it is at the
 * edge.
 */
export function moved<T>(items: T[], index: number, delta: -1 | 1): T[] | null {
  const target = index + delta;
  if (index < 0 || target < 0 || target >= items.length) return null;
  const next = [...items];
  [next[index], next[target]] = [next[target]!, next[index]!];
  return next;
}
