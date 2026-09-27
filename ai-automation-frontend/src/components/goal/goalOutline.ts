import type { GoalDetail, GoalMilestone, GoalProject, GoalTodoItem } from '../../types';

/**
 * Gliederung eines Ziels (c60bf914), rein und testbar: Projekt → Teilziel → TODOs, je Projekt dazu
 * „Ohne Teilziel“, am Ende „Ohne Projekt“. Jeder Knoten zählt seine TODOs.
 *
 * Gezählt werden nicht archivierte TODOs; offene Fragen nur an TODOs, die noch nicht erledigt sind.
 * Archivierte TODOs stehen auch nicht in der Gliederung.
 */

/** Schlüssel der Gruppe „Ohne Projekt“ – Projekt-IDs sind UUIDs, das kollidiert nicht. */
export const NO_PROJECT = 'none';

export interface OutlineCounts {
  total: number;
  done: number;
  openQuestions: number;
}

export interface OutlineTodo {
  todo: GoalTodoItem;
  /** Altfall (⚠): Das TODO hat ein anderes Projekt als sein Teilziel. Wird gelesen, nicht abgewiesen. */
  mismatch: boolean;
}

export interface OutlineSubgoal {
  milestone: GoalMilestone;
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
  subgoals: OutlineSubgoal[];
  /** TODOs des Projekts ohne Teilziel. */
  loose: OutlineTodo[];
  counts: OutlineCounts;
}

export interface GoalOutline {
  /** Die Projekte des Ziels nach Position, danach Projekte, die nur über ein TODO hereinkommen. */
  projects: OutlineProject[];
  /** „Ohne Projekt“ – nur, wenn es dort TODOs oder Teilziele gibt. */
  noProject: OutlineProject | null;
}

const shown = (todo: GoalTodoItem) => todo.status !== 'ARCHIVED';

export function countTodos(todos: GoalTodoItem[]): OutlineCounts {
  const counted = todos.filter(shown);
  return {
    total: counted.length,
    done: counted.filter((todo) => todo.status === 'DONE').length,
    openQuestions: counted.filter((todo) => todo.status !== 'DONE' && !!todo.openQuestion).length,
  };
}

const sumCounts = (parts: OutlineCounts[]): OutlineCounts => parts.reduce(
  (sum, part) => ({ total: sum.total + part.total, done: sum.done + part.done, openQuestions: sum.openQuestions + part.openQuestions }),
  { total: 0, done: 0, openQuestions: 0 },
);

/** Anteil erledigt in Prozent, 0 ohne TODOs. */
export function percentDone(counts: OutlineCounts): number {
  return counts.total === 0 ? 0 : Math.round((counts.done / counts.total) * 100);
}

/**
 * Teilziele nach `position`; bei gleicher Position bleibt die Reihenfolge des Servers.
 */
function byPosition(milestones: GoalMilestone[]): GoalMilestone[] {
  return milestones.map((milestone, index) => ({ milestone, index }))
    .sort((a, b) => a.milestone.position - b.milestone.position || a.index - b.index)
    .map(({ milestone }) => milestone);
}

export function buildOutline(detail: Pick<GoalDetail, 'projects' | 'milestones' | 'todos'>): GoalOutline {
  const projectKeys = new Set(detail.projects.map((project) => project.projectId));
  const milestoneById = new Map(detail.milestones.map((milestone) => [milestone.id, milestone]));
  /** Gruppe eines Teilziels: sein Projekt, sonst (Projekt gelöscht, Altdaten) „Ohne Projekt“. */
  const groupOfMilestone = (milestone: GoalMilestone) => milestone.projectId ?? NO_PROJECT;

  const todosBy = new Map<string, OutlineTodo[]>();
  const push = (key: string, entry: OutlineTodo) => todosBy.set(key, [...(todosBy.get(key) ?? []), entry]);
  const strays = new Map<string, string>();

  for (const todo of detail.todos.filter(shown)) {
    const milestone = todo.milestoneId ? milestoneById.get(todo.milestoneId) : undefined;
    if (milestone) {
      push(`ms:${milestone.id}`, { todo, mismatch: (todo.projectId ?? null) !== (milestone.projectId ?? null) });
      continue;
    }
    const key = todo.projectId ?? NO_PROJECT;
    if (todo.projectId && !projectKeys.has(todo.projectId)) strays.set(todo.projectId, todo.projectName ?? todo.projectId);
    push(`loose:${key}`, { todo, mismatch: false });
  }

  const group = (key: string, project: GoalProject | null, linked: boolean): OutlineProject => {
    const subgoals = byPosition(detail.milestones.filter((milestone) => groupOfMilestone(milestone) === key))
      .map((milestone): OutlineSubgoal => {
        const todos = todosBy.get(`ms:${milestone.id}`) ?? [];
        return { milestone, todos, counts: countTodos(todos.map((entry) => entry.todo)) };
      });
    const loose = todosBy.get(`loose:${key}`) ?? [];
    const counts = sumCounts([...subgoals.map((subgoal) => subgoal.counts), countTodos(loose.map((entry) => entry.todo))]);
    return { key, project, linked, subgoals, loose, counts };
  };

  // Teilziele, deren Projekt nicht (mehr) zum Ziel gehört, bekommen ihr Projekt als eigene Gruppe
  for (const milestone of detail.milestones) {
    if (milestone.projectId && !projectKeys.has(milestone.projectId) && !strays.has(milestone.projectId)) {
      const named = detail.todos.find((todo) => todo.projectId === milestone.projectId)?.projectName;
      strays.set(milestone.projectId, named ?? milestone.projectId);
    }
  }

  const ordered = [...detail.projects].sort((a, b) => a.position - b.position);
  const projects = [
    ...ordered.map((project) => group(project.projectId, project, true)),
    ...[...strays.entries()].map(([projectId, name], index) => group(projectId, {
      projectId, name, archived: false, position: ordered.length + index, total: 0, done: 0, openQuestions: 0,
    }, false)),
  ];
  const rest = group(NO_PROJECT, null, true);
  const noProject = rest.subgoals.length > 0 || rest.loose.length > 0 ? rest : null;
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
