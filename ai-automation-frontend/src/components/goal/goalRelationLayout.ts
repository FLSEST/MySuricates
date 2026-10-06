import type { GoalOriginKind, GoalTodoRelation, RunStatus, TodoResolutionState } from '../../types';

/**
 * Layout of the relation card of a goal (TODO c6ea8f5c), pure and testable: four columns
 * origin · TODOs · processing · result.
 *
 * - TODO rows in working order (as the server delivers them), grouped by project - with
 *   `MapStructure` by project → work package (52bc1e8c); the candidates come last in a
 *   group of their own.
 * - Origin and processing are merged: the same workflow (or „Mensch“, „Health-Check“ …)
 *   is one node, at the height of the mean of its TODOs; the lines converge in a fan shape.
 * - A TODO without processing gets a dotted stub („kein Workflow“) - this shows
 *   where nothing docks yet.
 */

export const COLUMNS = [
  { x: 0, w: 190 },
  { x: 250, w: 290 },
  { x: 600, w: 200 },
  { x: 860, w: 160 },
] as const;

export const MAP_WIDTH = COLUMNS[3].x + COLUMNS[3].w;
/** Space above the first row for the column heads. */
export const HEADER_HEIGHT = 34;
export const NODE_HEIGHT = 34;
export const ROW_HEIGHT = 44;
export const GROUP_LABEL_HEIGHT = 24;
const GAP_HEIGHT = 22;
const GAP_WIDTH = 110;
/** Minimum distance between merged nodes of a column. */
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
  /** Text from the data (workflow name, TODO title, agent); if absent, the card labels by kind. */
  label: string | null;
  tone: Tone;
  /** The TODOs whose path runs through this node - basis of the highlighting. */
  todoIds: string[];
  candidate: boolean;
  target: NodeTarget | null;
  originKind?: GoalOriginKind;
  /** Processing: fix workflow and/or trigger. */
  workerKinds?: ('LINKED' | 'TRIGGER')[];
  triggerLabels?: string[];
  runStatus?: RunStatus | null;
  resolutionState?: TodoResolutionState | null;
  fixAttempts?: number;
  /** TODO: status and priority for the symbols. */
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
  /** Project name; `null` = without project; for `candidate` the candidate group. */
  label: string | null;
  y: number;
  /** Height of the group including its rows - drop surface of the workflow assignment. */
  h?: number;
  candidate: boolean;
  /**
   * Outline by work packages (52bc1e8c): the package of the group; `null` = „Ohne Arbeitspaket“;
   * absent if outlined by project only.
   */
  workPackageId?: string | null;
  /** Title of the package (or `null` for „Ohne Arbeitspaket“) - stands after the project name. */
  packageTitle?: string | null;
}

/**
 * How the rows are outlined (52bc1e8c): project → work package, in the order of the
 * projects and packages of the goal, per project „Ohne Arbeitspaket“ last. The relations do not know
 * the package of a TODO - it comes from the goal (`GoalDetail.todos[].workPackageId`).
 */
export interface MapStructure {
  /** The goal's projects in their order. */
  projectOrder: string[];
  /** Packages of the goal in their order (per project by position). */
  packages: { id: string; title: string; projectId: string | null }[];
  /** Package per TODO; a missing TODO counts as without a package. */
  packageOf: ReadonlyMap<string, string | null>;
}

export interface RelationLayout {
  width: number;
  height: number;
  nodes: MapNode[];
  edges: MapEdge[];
  groups: MapGroup[];
  /** Does at least one TODO of the goal (without candidates) have a workflow that processes it? */
  hasWorkers: boolean;
}

/** Key of the merged origin node. */
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

/** Curve from the right edge of the source to the left edge of the target. */
export function curve(x1: number, y1: number, x2: number, y2: number): string {
  const mid = (x1 + x2) / 2;
  return `M ${x1} ${y1} C ${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}`;
}

const centerY = (node: MapNode) => node.y + node.h / 2;

/** Merged node, still without final height. */
interface Pending {
  node: MapNode;
  desired: number[];
}

/**
 * Distributes the nodes of a column: each as close as possible to the mean of its TODOs, pushed
 * down on overlap. Returns the bottom edge.
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

/** Groups of the rows: by project (without structure) or by project → work package. */
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
    // Packages in the order of the goal; a package of another project (legacy case) also stands under the TODO project
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

  // ── TODO rows: grouped by project, groups in the order of their first TODO ──
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

  // ── Origin: one node per workflow or kind ──
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
    // Several runs: the worst status counts (a failed run should stand out).
    if (todo.origin.runStatus === 'FAILED') entry.node.runStatus = 'FAILED';
  }
  for (const entry of origins.values()) {
    if (entry.node.originKind !== 'AUDIT') continue;
    const findings = [...new Set(todos.filter((t) => entry.node.todoIds.includes(t.id)).flatMap((t) => t.origin.findingIds))];
    entry.node.target = findings.length === 1 ? { type: 'finding', id: findings[0]! } : { type: 'audit' };
  }

  // ── Processing: one node per workflow, plus a stub per TODO without a workflow ──
  const workers = new Map<string, Pending>();
  const gaps: Pending[] = [];
  for (const todo of todos) {
    const todoNode = todoNodes.get(todo.id)!;
    if (todo.workers.length === 0) {
      if (todo.result) continue; // already processed, nothing docks there any more today: line directly to the result
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

  // ── Result: one node per TODO with a fix run, on its row ──
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

/** The TODO IDs that are highlighted when hovering over a node. */
export function highlightFor(layout: RelationLayout, nodeId: string | null): Set<string> | null {
  if (!nodeId) return null;
  const node = layout.nodes.find((n) => n.id === nodeId);
  return node ? new Set(node.todoIds) : null;
}
