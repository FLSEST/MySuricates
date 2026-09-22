/**
 * Geometry and step logic of the factory flow diagram (FactoryFlowDiagram).
 * Mirrors WorkflowFactoryService.runFactory: generate on DEV → sync → test run on the
 * Test instance → (auto-fix on DEV → sync → test again, up to maxRetries) → result on DEV
 * → optional template. The plain "create" mode only runs the first step.
 */

export type FactoryMode = 'create' | 'createAndTest';

export type FlowNodeId = 'generate' | 'test' | 'repair' | 'result' | 'template';
export type FlowEdgeId = 'sync' | 'fail' | 'retry' | 'pass' | 'promote';

type Point = readonly [number, number];

export interface FlowNode {
  id: FlowNodeId;
  x: number;
  y: number;
  w: number;
  h: number;
  lane: 'dev' | 'test';
  tone: 'ai' | 'info' | 'success' | 'neutral';
}

export const VIEW_W = 640;
export const VIEW_H = 350;
/** DEV lane spans [0, LANE_SPLIT - gap], TEST lane the rest. */
export const LANE_SPLIT = 330;

export const NODES: Record<FlowNodeId, FlowNode> = {
  generate: { id: 'generate', x: 30, y: 58, w: 220, h: 48, lane: 'dev', tone: 'ai' },
  test: { id: 'test', x: 390, y: 118, w: 220, h: 48, lane: 'test', tone: 'info' },
  repair: { id: 'repair', x: 30, y: 188, w: 220, h: 48, lane: 'dev', tone: 'ai' },
  result: { id: 'result', x: 150, y: 280, w: 100, h: 44, lane: 'dev', tone: 'success' },
  template: { id: 'template', x: 30, y: 280, w: 100, h: 44, lane: 'dev', tone: 'neutral' },
};

export const EDGES: Record<FlowEdgeId, readonly Point[]> = {
  sync: [[250, 82], [320, 82], [320, 136], [390, 136]],
  fail: [[500, 166], [500, 222], [250, 222]],
  retry: [[250, 200], [300, 200], [300, 150], [390, 150]],
  pass: [[610, 142], [626, 142], [626, 302], [250, 302]],
  promote: [[150, 302], [130, 302]],
};

export function pointsToPath(points: readonly Point[]): string {
  return points.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x},${y}`).join(' ');
}

function center(id: FlowNodeId): Point {
  const n = NODES[id];
  return [n.x + n.w / 2, n.y + n.h / 2];
}

export interface FlowOptions {
  mode: FactoryMode;
  autoFix: boolean;
  autoPromote: boolean;
}

/** Which nodes/edges actually happen with the given options (the rest is drawn dimmed). */
export function activeParts({ mode, autoFix, autoPromote }: FlowOptions): Set<FlowNodeId | FlowEdgeId> {
  if (mode === 'create') return new Set(['generate']);
  const parts = new Set<FlowNodeId | FlowEdgeId>(['generate', 'sync', 'test', 'pass', 'result']);
  if (autoFix) ['fail', 'repair', 'retry'].forEach(p => parts.add(p as FlowEdgeId));
  if (autoPromote) ['promote', 'template'].forEach(p => parts.add(p as FlowNodeId));
  return parts;
}

/**
 * The route the animated token takes: one failing test that gets repaired (if auto-fix is
 * on), then a passing test. Returns null in "create" mode — nothing travels there.
 */
export function tokenRoute({ mode, autoFix, autoPromote }: FlowOptions): Point[] | null {
  if (mode === 'create') return null;
  const route: Point[] = [center('generate'), ...EDGES.sync, center('test')];
  if (autoFix) {
    route.push(...EDGES.fail, center('repair'), ...EDGES.retry, center('test'));
  }
  route.push(...EDGES.pass, center('result'));
  if (autoPromote) route.push(...EDGES.promote, center('template'));
  return route;
}

/** Seconds per route; each segment gets roughly the same share so the pace stays even. */
export function routeDuration(route: readonly Point[]): number {
  let length = 0;
  route.forEach(([x, y], i) => {
    const prev = route[i - 1];
    if (prev) length += Math.hypot(x - prev[0], y - prev[1]);
  });
  return Math.max(3, Math.round(length / 160));
}
