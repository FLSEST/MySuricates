import type { GoalDetail, GoalProject, GoalTodoItem, GoalWorkPackage } from '../../types';

/**
 * Gliederung eines Ziels (c60bf914), rein und testbar: Projekt → Arbeitspaket → TODOs, je Projekt
 * dazu „Ohne Arbeitspaket“, am Ende „Ohne Projekt“. Jeder Knoten zählt seine TODOs. Die Meilensteine
 * stehen darüber für sich (sie gehören dem ganzen Ziel, keinem Projekt).
 *
 * Gezählt werden nicht archivierte TODOs; offene Fragen nur an TODOs, die noch nicht erledigt sind,
 * Entscheidungen an allen (68a5e24e, wie der Server).
 * Archivierte TODOs stehen auch nicht in der Gliederung.
 */

/** Schlüssel der Gruppe „Ohne Projekt“ – Projekt-IDs sind UUIDs, das kollidiert nicht. */
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
  /** Altfall (⚠): Das TODO hat ein anderes Projekt als sein Arbeitspaket. Wird gelesen, nicht abgewiesen. */
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
  /** null für „Ohne Projekt“. */
  project: GoalProject | null;
  /** Das Projekt eines TODOs gehört (noch) nicht zum Ziel – nur Altdaten, sonst hängt der Server es an. */
  linked: boolean;
  /** In Reihenfolge ihrer Position. */
  packages: OutlinePackage[];
  /** TODOs des Projekts ohne Arbeitspaket. */
  loose: OutlineTodo[];
  counts: OutlineCounts;
}

export interface GoalOutline {
  /** Die Projekte des Ziels nach Position, danach Projekte, die nur über ein TODO hereinkommen. */
  projects: OutlineProject[];
  /** „Ohne Projekt“ – nur, wenn es dort TODOs oder Arbeitspakete gibt. */
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

/** Pakete nach `position`; bei gleicher Position bleibt die Reihenfolge des Servers. */
function byPosition(workPackages: GoalWorkPackage[]): GoalWorkPackage[] {
  return workPackages.map((item, index) => ({ item, index }))
    .sort((a, b) => a.item.position - b.item.position || a.index - b.index)
    .map(({ item }) => item);
}

export function buildOutline(detail: Pick<GoalDetail, 'projects' | 'workPackages' | 'todos'>): GoalOutline {
  const projectKeys = new Set(detail.projects.map((project) => project.projectId));
  const packageById = new Map(detail.workPackages.map((item) => [item.id, item]));
  /** Gruppe eines Pakets: sein Projekt, sonst (Projekt gelöscht, Altdaten) „Ohne Projekt“. */
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

  // Pakete, deren Projekt nicht (mehr) zum Ziel gehört, bekommen ihr Projekt als eigene Gruppe
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
 * Neue Reihenfolge nach ↑/↓: das Element an `index` tauscht mit seinem Nachbarn. null, wenn es am
 * Rand steht.
 */
export function moved<T>(items: T[], index: number, delta: -1 | 1): T[] | null {
  const target = index + delta;
  if (index < 0 || target < 0 || target >= items.length) return null;
  const next = [...items];
  [next[index], next[target]] = [next[target]!, next[index]!];
  return next;
}
