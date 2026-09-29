import type { GoalOriginKind, GoalTodoRelation, RunStatus, TodoResolutionState } from '../../types';

/**
 * Layout der Relationskarte eines Ziels (TODO c6ea8f5c), rein und testbar: vier Spalten
 * Herkunft · TODOs · Bearbeitung · Ergebnis.
 *
 * - TODO-Zeilen in Arbeitsreihenfolge (so liefert sie der Server), nach Projekt gruppiert – mit
 *   `MapStructure` nach Projekt → Arbeitspaket (52bc1e8c); die Kandidaten stehen zuletzt in einer
 *   eigenen Gruppe.
 * - Herkunft und Bearbeitung sind zusammengefasst: derselbe Workflow (oder „Mensch“, „Health-Check“ …)
 *   ist ein Knoten, auf Höhe des Mittels seiner TODOs; die Linien laufen fächerförmig zusammen.
 * - Ein TODO ohne Bearbeitung bekommt einen gepunkteten Stummel („kein Workflow“) – so sieht man,
 *   wo noch nichts andockt.
 */

export const COLUMNS = [
  { x: 0, w: 190 },
  { x: 250, w: 290 },
  { x: 600, w: 200 },
  { x: 860, w: 160 },
] as const;

export const MAP_WIDTH = COLUMNS[3].x + COLUMNS[3].w;
/** Platz über der ersten Zeile für die Spaltenköpfe. */
export const HEADER_HEIGHT = 34;
export const NODE_HEIGHT = 34;
export const ROW_HEIGHT = 44;
export const GROUP_LABEL_HEIGHT = 24;
const GAP_HEIGHT = 22;
const GAP_WIDTH = 110;
/** Mindestabstand zwischen zusammengefassten Knoten einer Spalte. */
const NODE_SPACING = 8;

export type NodeKind = 'origin' | 'todo' | 'worker' | 'result' | 'gap';
export type Tone = 'human' | 'ai' | 'neutral' | 'success' | 'warning' | 'danger' | 'info' | 'muted';
export type EdgeStyle = 'solid' | 'dashed' | 'dotted';

export type NodeTarget =
  | { type: 'workflow'; id: string }
  | { type: 'run'; id: string }
  | { type: 'finding'; id: string }
  | { type: 'audit' }
  | { type: 'todo'; id: string };

export interface MapNode {
  id: string;
  kind: NodeKind;
  column: 0 | 1 | 2 | 3;
  x: number;
  y: number;
  w: number;
  h: number;
  /** Text aus den Daten (Workflow-Name, TODO-Titel, Agent); fehlt er, beschriftet die Karte nach Art. */
  label: string | null;
  tone: Tone;
  /** Die TODOs, deren Pfad durch diesen Knoten läuft – Grundlage der Hervorhebung. */
  todoIds: string[];
  candidate: boolean;
  target: NodeTarget | null;
  originKind?: GoalOriginKind;
  /** Bearbeitung: Fix-Workflow und/oder Trigger. */
  workerKinds?: ('LINKED' | 'TRIGGER')[];
  triggerLabels?: string[];
  runStatus?: RunStatus | null;
  resolutionState?: TodoResolutionState | null;
  fixAttempts?: number;
  /** TODO: Status und Priorität für die Symbole. */
  todo?: GoalTodoRelation;
}

export interface MapEdge {
  id: string;
  from: string;
  to: string;
  path: string;
  style: EdgeStyle;
  todoId: string;
}

export interface MapGroup {
  /** Projektname; `null` = ohne Projekt; bei `candidate` die Kandidaten-Gruppe. */
  label: string | null;
  y: number;
  /** Höhe der Gruppe samt ihrer Zeilen – Ablagefläche der Workflow-Zuordnung. */
  h?: number;
  candidate: boolean;
  /**
   * Gliederung nach Arbeitspaketen (52bc1e8c): das Paket der Gruppe; `null` = „Ohne Arbeitspaket“;
   * fehlt, wenn nur nach Projekt gegliedert ist.
   */
  workPackageId?: string | null;
  /** Titel des Pakets (oder `null` für „Ohne Arbeitspaket“) – steht hinter dem Projektnamen. */
  packageTitle?: string | null;
}

/**
 * Wie die Zeilen gegliedert werden (52bc1e8c): Projekt → Arbeitspaket, in der Reihenfolge der
 * Projekte und Pakete des Ziels, je Projekt zuletzt „Ohne Arbeitspaket“. Die Relationen kennen
 * das Paket eines TODOs nicht – es kommt aus dem Ziel (`GoalDetail.todos[].workPackageId`).
 */
export interface MapStructure {
  /** Projekte des Ziels in ihrer Reihenfolge. */
  projectOrder: string[];
  /** Pakete des Ziels in ihrer Reihenfolge (je Projekt nach Position). */
  packages: { id: string; title: string; projectId: string | null }[];
  /** Paket je TODO; fehlt ein TODO, gilt es als ohne Paket. */
  packageOf: ReadonlyMap<string, string | null>;
}

export interface RelationLayout {
  width: number;
  height: number;
  nodes: MapNode[];
  edges: MapEdge[];
  groups: MapGroup[];
  /** Hat mindestens ein TODO des Ziels (ohne Kandidaten) einen Workflow, der es bearbeitet? */
  hasWorkers: boolean;
}

/** Schlüssel des zusammengefassten Herkunfts-Knotens. */
export function originKey(todo: GoalTodoRelation): string {
  const { kind, workflowId, agent } = todo.origin;
  switch (kind) {
    case 'WORKFLOW_RUN': return `origin:wf:${workflowId ?? '?'}`;
    case 'AUDIT': return `origin:audit:${workflowId ?? '?'}`;
    case 'AGENT': return `origin:agent:${agent ?? '?'}`;
    case 'HEALTH': return 'origin:health';
    default: return 'origin:user';
  }
}

export function resultTone(runStatus: RunStatus | null, state: TodoResolutionState | null): Tone {
  switch (runStatus) {
    case 'COMPLETED': return 'success';
    case 'COMPLETED_WITH_ERRORS': return 'warning';
    case 'FAILED': case 'CANCELLED': case 'INTERRUPTED': return 'danger';
    case 'RUNNING': case 'PENDING': case 'PAUSED': return 'info';
    default:
      if (state === 'SUCCEEDED') return 'success';
      if (state === 'FAILED') return 'danger';
      if (state === 'RUNNING') return 'info';
      return 'neutral';
  }
}

function originTone(kind: GoalOriginKind): Tone {
  if (kind === 'USER') return 'human';
  if (kind === 'AGENT') return 'ai';
  return 'neutral';
}

function todoTone(todo: GoalTodoRelation): Tone {
  if (todo.candidate) return 'muted';
  if (todo.status === 'DONE') return 'success';
  if (todo.status === 'IN_PROGRESS') return 'info';
  return 'neutral';
}

/** Kurve von der rechten Kante der Quelle zur linken Kante des Ziels. */
export function curve(x1: number, y1: number, x2: number, y2: number): string {
  const mid = (x1 + x2) / 2;
  return `M ${x1} ${y1} C ${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}`;
}

const centerY = (node: MapNode) => node.y + node.h / 2;

/** Zusammengefasster Knoten, noch ohne endgültige Höhe. */
interface Pending {
  node: MapNode;
  desired: number[];
}

/**
 * Verteilt die Knoten einer Spalte: jeder möglichst auf Höhe des Mittels seiner TODOs, bei
 * Überlappung nach unten geschoben. Gibt die unterste Kante zurück.
 */
function place(items: Pending[]): number {
  const wanted = items.map((item) => ({
    item,
    y: item.desired.reduce((a, b) => a + b, 0) / item.desired.length - item.node.h / 2,
  }));
  wanted.sort((a, b) => a.y - b.y);
  let bottom = -Infinity;
  for (const { item, y } of wanted) {
    item.node.y = Math.max(y, bottom + NODE_SPACING);
    bottom = item.node.y + item.node.h;
  }
  return bottom;
}

/** Gruppen der Zeilen: nach Projekt (ohne Struktur) oder nach Projekt → Arbeitspaket. */
function rowGroupsOf(members: GoalTodoRelation[], structure?: MapStructure) {
  type RowGroup = { label: string | null; candidate: boolean; todos: GoalTodoRelation[]; workPackageId?: string | null; packageTitle?: string | null };
  const byProject = new Map<string, GoalTodoRelation[]>();
  for (const todo of members) {
    const key = todo.projectId ?? '';
    byProject.set(key, [...(byProject.get(key) ?? []), todo]);
  }
  if (!structure) {
    return [...byProject.values()].map((list): RowGroup => ({ label: list[0]!.projectName, candidate: false, todos: list }));
  }
  const rank = (projectId: string) => {
    const index = structure.projectOrder.indexOf(projectId);
    return index < 0 ? Number.MAX_SAFE_INTEGER : index;
  };
  const projects = [...byProject.keys()].map((key, index) => ({ key, index }))
    .sort((a, b) => rank(a.key) - rank(b.key) || a.index - b.index).map(({ key }) => key);
  const groups: RowGroup[] = [];
  for (const projectId of projects) {
    const list = byProject.get(projectId)!;
    const label = list[0]!.projectName;
    const packageOf = (todo: GoalTodoRelation) => {
      const id = structure.packageOf.get(todo.id) ?? null;
      return id && structure.packages.some((item) => item.id === id) ? id : null;
    };
    // Pakete in der Reihenfolge des Ziels; auch ein Paket eines anderen Projekts (Altfall) steht unter dem TODO-Projekt
    const packageIds = [...new Set(list.map(packageOf).filter((id): id is string => id !== null))]
      .sort((a, b) => structure.packages.findIndex((item) => item.id === a) - structure.packages.findIndex((item) => item.id === b));
    for (const id of packageIds) {
      groups.push({
        label, candidate: false, todos: list.filter((todo) => packageOf(todo) === id), workPackageId: id,
        packageTitle: structure.packages.find((item) => item.id === id)!.title,
      });
    }
    const loose = list.filter((todo) => packageOf(todo) === null);
    if (loose.length) groups.push({ label, candidate: false, todos: loose, workPackageId: null, packageTitle: null });
  }
  return groups;
}

export function layoutRelations(todos: GoalTodoRelation[], structure?: MapStructure): RelationLayout {
  const nodes: MapNode[] = [];
  const edges: MapEdge[] = [];
  const groups: MapGroup[] = [];

  // ── TODO-Zeilen: nach Projekt gruppiert, Gruppen in der Reihenfolge ihres ersten TODOs ──
  const members = todos.filter((t) => !t.candidate);
  const candidates = todos.filter((t) => t.candidate);
  const rowGroups = rowGroupsOf(members, structure);
  if (candidates.length) rowGroups.push({ label: null, candidate: true, todos: candidates });

  const todoNodes = new Map<string, MapNode>();
  let cursor = HEADER_HEIGHT;
  for (const group of rowGroups) {
    groups.push({
      label: group.label, y: cursor, candidate: group.candidate,
      ...(group.workPackageId !== undefined ? { workPackageId: group.workPackageId, packageTitle: group.packageTitle ?? null } : {}),
    });
    cursor += GROUP_LABEL_HEIGHT;
    for (const todo of group.todos) {
      const node: MapNode = {
        id: `todo:${todo.id}`, kind: 'todo', column: 1, x: COLUMNS[1].x, y: cursor, w: COLUMNS[1].w, h: NODE_HEIGHT,
        label: todo.title, tone: todoTone(todo), todoIds: [todo.id], candidate: todo.candidate,
        target: { type: 'todo', id: todo.id }, todo,
      };
      todoNodes.set(todo.id, node);
      nodes.push(node);
      cursor += ROW_HEIGHT;
    }
  }
  const rowsBottom = cursor;
  groups.forEach((group, index) => { group.h = (groups[index + 1]?.y ?? rowsBottom) - group.y; });

  // ── Herkunft: ein Knoten je Workflow bzw. Art ──
  const origins = new Map<string, Pending>();
  for (const todo of todos) {
    const key = originKey(todo);
    const todoNode = todoNodes.get(todo.id)!;
    let entry = origins.get(key);
    if (!entry) {
      const { kind, workflowId, workflowName, agent } = todo.origin;
      entry = {
        node: {
          id: key, kind: 'origin', column: 0, x: COLUMNS[0].x, y: 0, w: COLUMNS[0].w, h: NODE_HEIGHT,
          label: kind === 'AGENT' ? agent : workflowName, tone: originTone(kind), todoIds: [], candidate: false,
          target: workflowId && kind === 'WORKFLOW_RUN' ? { type: 'workflow', id: workflowId } : null,
          originKind: kind, runStatus: todo.origin.runStatus,
        },
        desired: [],
      };
      origins.set(key, entry);
    }
    entry.node.todoIds.push(todo.id);
    entry.desired.push(centerY(todoNode));
    // Mehrere Läufe: der schlechteste Status zählt (ein gescheiterter Lauf soll auffallen).
    if (todo.origin.runStatus === 'FAILED') entry.node.runStatus = 'FAILED';
  }
  for (const entry of origins.values()) {
    if (entry.node.originKind !== 'AUDIT') continue;
    const findings = [...new Set(todos.filter((t) => entry.node.todoIds.includes(t.id)).flatMap((t) => t.origin.findingIds))];
    entry.node.target = findings.length === 1 ? { type: 'finding', id: findings[0]! } : { type: 'audit' };
  }

  // ── Bearbeitung: ein Knoten je Workflow, dazu ein Stummel je TODO ohne Workflow ──
  const workers = new Map<string, Pending>();
  const gaps: Pending[] = [];
  for (const todo of todos) {
    const todoNode = todoNodes.get(todo.id)!;
    if (todo.workers.length === 0) {
      if (todo.result) continue; // schon bearbeitet, nur dockt heute nichts mehr an: Linie direkt zum Ergebnis
      gaps.push({
        node: {
          id: `gap:${todo.id}`, kind: 'gap', column: 2, x: COLUMNS[2].x, y: 0, w: GAP_WIDTH, h: GAP_HEIGHT,
          label: null, tone: 'muted', todoIds: [todo.id], candidate: todo.candidate, target: null,
        },
        desired: [centerY(todoNode)],
      });
      continue;
    }
    for (const worker of todo.workers) {
      const key = `worker:${worker.workflowId}`;
      let entry = workers.get(key);
      if (!entry) {
        entry = {
          node: {
            id: key, kind: 'worker', column: 2, x: COLUMNS[2].x, y: 0, w: COLUMNS[2].w, h: NODE_HEIGHT,
            label: worker.workflowName, tone: 'neutral', todoIds: [], candidate: false,
            target: { type: 'workflow', id: worker.workflowId }, workerKinds: [], triggerLabels: [],
          },
          desired: [],
        };
        workers.set(key, entry);
      }
      const node = entry.node;
      if (!node.workerKinds!.includes(worker.kind)) node.workerKinds!.push(worker.kind);
      if (worker.triggerLabel && !node.triggerLabels!.includes(worker.triggerLabel)) node.triggerLabels!.push(worker.triggerLabel);
      if (!node.todoIds.includes(todo.id)) {
        node.todoIds.push(todo.id);
        entry.desired.push(centerY(todoNode));
      }
    }
  }

  // ── Ergebnis: je TODO mit Fix-Lauf ein Knoten auf seiner Zeile ──
  const results: MapNode[] = [];
  for (const todo of todos) {
    if (!todo.result) continue;
    const { runId, runStatus, resolutionState, fixAttempts } = todo.result;
    const todoNode = todoNodes.get(todo.id)!;
    results.push({
      id: `result:${todo.id}`, kind: 'result', column: 3, x: COLUMNS[3].x, y: todoNode.y, w: COLUMNS[3].w, h: NODE_HEIGHT,
      label: null, tone: resultTone(runStatus, resolutionState), todoIds: [todo.id], candidate: todo.candidate,
      target: runId ? { type: 'run', id: runId } : null, runStatus, resolutionState, fixAttempts,
    });
  }

  const bottoms = [rowsBottom, place([...origins.values()]), place([...workers.values(), ...gaps])];
  nodes.push(...[...origins.values()].map((e) => e.node), ...[...workers.values()].map((e) => e.node),
    ...gaps.map((e) => e.node), ...results);

  // ── Kanten ──
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const connect = (from: MapNode, to: MapNode, todo: GoalTodoRelation, style: EdgeStyle) => {
    edges.push({
      id: `${from.id}->${to.id}#${todo.id}`, from: from.id, to: to.id, todoId: todo.id, style,
      path: curve(from.x + from.w, centerY(from), to.x, centerY(to)),
    });
  };
  for (const todo of todos) {
    const todoNode = todoNodes.get(todo.id)!;
    const style: EdgeStyle = todo.candidate ? 'dashed' : 'solid';
    connect(byId.get(originKey(todo))!, todoNode, todo, style);
    const gap = byId.get(`gap:${todo.id}`);
    if (gap) connect(todoNode, gap, todo, 'dotted');
    const workerIds = [...new Set(todo.workers.map((w) => `worker:${w.workflowId}`))];
    workerIds.forEach((id) => connect(todoNode, byId.get(id)!, todo, style));
    const result = byId.get(`result:${todo.id}`);
    if (result) {
      const fixWorker = todo.result?.workflowId ? byId.get(`worker:${todo.result.workflowId}`) : undefined;
      const source = fixWorker && workerIds.includes(fixWorker.id) ? fixWorker : todoNode;
      connect(source, result, todo, style);
    }
  }

  return {
    width: MAP_WIDTH,
    height: Math.max(...bottoms, HEADER_HEIGHT) + NODE_SPACING,
    nodes,
    edges,
    groups,
    hasWorkers: members.some((t) => t.workers.length > 0),
  };
}

/** Die TODO-IDs, die bei Hover über einem Knoten hervorgehoben werden. */
export function highlightFor(layout: RelationLayout, nodeId: string | null): Set<string> | null {
  if (!nodeId) return null;
  const node = layout.nodes.find((n) => n.id === nodeId);
  return node ? new Set(node.todoIds) : null;
}
