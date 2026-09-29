import type { KeyboardCoordinateGetter } from '@dnd-kit/core';
import type { GoalDetail, GoalWorkflow } from '../../types';
import type { MapStructure } from './goalRelationLayout';

/**
 * Workflow-Zuordnung (52bc1e8c, Flo: „könnte man das so machen, dass man den Workflow auch ziehen
 * kann“): Workflows aus der Leiste rechts auf die Karte ziehen – rein und testbar.
 *
 * - auf ein TODO: dieser Workflow bearbeitet es (`linkedWorkflowId`, der Fix-Workflow am TODO);
 * - auf ein Arbeitspaket oder die Kopfzeile des Ziels: Zuordnung wie in Stufe 3 (Rolle WORKER).
 */

export type MapTarget =
  | { kind: 'todo'; todoId: string }
  | { kind: 'package'; workPackageId: string }
  | { kind: 'goal' };

export const todoTargetId = (todoId: string) => `wm-todo:${todoId}`;
export const packageTargetId = (workPackageId: string) => `wm-pkg:${workPackageId}`;
export const GOAL_TARGET_ID = 'wm-goal';
export const workflowDragId = (workflowId: string) => `wm-wf:${workflowId}`;

export function parseTarget(id: string | number | null | undefined): MapTarget | null {
  if (id === GOAL_TARGET_ID) return { kind: 'goal' };
  const value = String(id ?? '');
  if (value.startsWith('wm-todo:') && value.length > 8) return { kind: 'todo', todoId: value.slice(8) };
  if (value.startsWith('wm-pkg:') && value.length > 7) return { kind: 'package', workPackageId: value.slice(7) };
  return null;
}

/** Der gezogene Workflow – so viel braucht die Karte von ihm. */
export interface DraggedWorkflow {
  id: string;
  name: string;
  projectId: string | null;
  projectName: string | null;
}

/** Was die Karte über ihre Ziele weiß. */
export interface MapContext {
  goalTitle: string;
  /** TODOs der Karte: Titel, Projekt, Kandidat, verknüpfter Workflow (Fix-Workflow). */
  todos: ReadonlyMap<string, { title: string; projectId: string | null; projectName: string | null; candidate: boolean; linkedWorkflowId: string | null }>;
  packages: ReadonlyMap<string, { title: string }>;
  /** Das Backend kennt Workflows am Ziel (sonst nur die Verknüpfung am TODO). */
  attachSupported: boolean;
  /** Vorhandene Zuordnungen am Ziel und an Paketen. */
  attachments: readonly Pick<GoalWorkflow, 'workflowId' | 'workPackageId' | 'id' | 'guard'>[];
}

export type Verdict = 'ok' | 'same' | 'refused';

/**
 * Taugt das Ziel für den Workflow? `same` = dort hängt er schon (nichts zu tun), `refused` = hier
 * nicht (Kandidat ohne Ziel, altes Backend, unbekanntes Ziel).
 */
export function verdict(workflow: DraggedWorkflow, target: MapTarget, ctx: MapContext): Verdict {
  if (target.kind === 'todo') {
    const todo = ctx.todos.get(target.todoId);
    if (!todo || todo.candidate) return 'refused';
    return todo.linkedWorkflowId === workflow.id ? 'same' : 'ok';
  }
  if (!ctx.attachSupported) return 'refused';
  const workPackageId = target.kind === 'package' ? target.workPackageId : null;
  if (workPackageId && !ctx.packages.has(workPackageId)) return 'refused';
  return ctx.attachments.some((item) => item.workflowId === workflow.id && (item.workPackageId ?? null) === workPackageId)
    ? 'same' : 'ok';
}

export interface MapLiveTexts {
  todo: string;
  todoOtherProject: string;
  package: string;
  goal: string;
  same: string;
  candidate: string;
  unsupported: string;
  outside: string;
}

/** Die Live-Zeile unter dem gezogenen Workflow: wohin er wirkt – oder warum nicht. */
export function liveLine(workflow: DraggedWorkflow, target: MapTarget | null, ctx: MapContext, texts: MapLiveTexts): { text: string; refused: boolean } {
  if (!target) return { text: texts.outside, refused: true };
  const result = verdict(workflow, target, ctx);
  if (result === 'same') return { text: texts.same, refused: true };
  if (target.kind === 'todo') {
    const todo = ctx.todos.get(target.todoId);
    if (!todo) return { text: texts.outside, refused: true };
    if (todo.candidate) return { text: texts.candidate, refused: true };
    const foreign = workflow.projectId && todo.projectId && workflow.projectId !== todo.projectId;
    return {
      text: (foreign ? texts.todoOtherProject : texts.todo).replace('{title}', todo.title)
        .replace('{project}', workflow.projectName ?? ''),
      refused: false,
    };
  }
  if (result === 'refused') return { text: texts.unsupported, refused: true };
  if (target.kind === 'package') {
    return { text: texts.package.replace('{title}', ctx.packages.get(target.workPackageId)?.title ?? ''), refused: false };
  }
  return { text: texts.goal.replace('{title}', ctx.goalTitle), refused: false };
}

/** Gliederung der Karte aus dem Ziel: Projekte, Pakete und das Paket je TODO. */
export function structureOf(detail: Pick<GoalDetail, 'projects' | 'workPackages' | 'todos'>): MapStructure {
  const projectOrder = [...detail.projects].sort((a, b) => a.position - b.position).map((project) => project.projectId);
  const rank = (projectId: string | null) => {
    const index = projectId ? projectOrder.indexOf(projectId) : -1;
    return index < 0 ? Number.MAX_SAFE_INTEGER : index;
  };
  const packages = [...detail.workPackages]
    .sort((a, b) => rank(a.projectId) - rank(b.projectId) || a.position - b.position)
    .map(({ id, title, projectId }) => ({ id, title, projectId }));
  return { projectOrder, packages, packageOf: new Map(detail.todos.map((todo) => [todo.id, todo.workPackageId])) };
}

/**
 * Tastatur beim Ziehen: statt pixelweise springen die Pfeiltasten von Ziel zu Ziel – ↓ → zum
 * nächsten, ↑ ← zum vorigen, in der Reihenfolge der Karte (oben nach unten, links nach rechts).
 * Gilt nur für die Ziele der Workflow-Zuordnung.
 */
export const targetCycleCoordinates: KeyboardCoordinateGetter = (event, { context, currentCoordinates }) => {
  const forward = event.code === 'ArrowDown' || event.code === 'ArrowRight';
  const backward = event.code === 'ArrowUp' || event.code === 'ArrowLeft';
  if (!forward && !backward) return undefined;
  event.preventDefault();
  const { active, collisionRect, droppableRects, droppableContainers, over } = context;
  if (!active || !collisionRect) return undefined;
  const targets = droppableContainers.getEnabled()
    .filter((container) => parseTarget(container.id) !== null)
    .map((container) => ({ id: container.id, rect: droppableRects.get(container.id) }))
    .filter((entry): entry is { id: typeof entry.id; rect: NonNullable<typeof entry.rect> } => !!entry.rect)
    .sort((a, b) => a.rect.top - b.rect.top || a.rect.left - b.rect.left);
  if (targets.length === 0) return currentCoordinates;
  const index = over ? targets.findIndex((entry) => entry.id === over.id) : -1;
  const next = index < 0 ? (forward ? 0 : targets.length - 1)
    : (index + (forward ? 1 : -1) + targets.length) % targets.length;
  const rect = targets[next]!.rect;
  // Die Mitte des gezogenen Elements auf die Mitte des Ziels
  return {
    x: rect.left + rect.width / 2 - collisionRect.width / 2,
    y: rect.top + rect.height / 2 - collisionRect.height / 2,
  };
};
