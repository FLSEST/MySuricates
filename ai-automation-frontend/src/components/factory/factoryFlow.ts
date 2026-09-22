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

/** One stretch of the animation: the token dwells in a node or travels along an edge. */
export interface FlowLeg {
  part: FlowNodeId | FlowEdgeId;
  /** Index into the numbered step list below the diagram. */
  step: number;
  points: readonly Point[];
  seconds: number;
}

const DWELL_SECONDS = 1.2;
const PX_PER_SECOND = 170;

function pathLength(points: readonly Point[]): number {
  let length = 0;
  points.forEach(([x, y], i) => {
    const prev = points[i - 1];
    if (prev) length += Math.hypot(x - prev[0], y - prev[1]);
  });
  return length;
}

function dwell(id: FlowNodeId, step: number): FlowLeg {
  return { part: id, step, points: [center(id)], seconds: DWELL_SECONDS };
}

function travel(id: FlowEdgeId, from: FlowNodeId, to: FlowNodeId, step: number): FlowLeg {
  const points = [center(from), ...EDGES[id], center(to)];
  return { part: id, step, points, seconds: pathLength(points) / PX_PER_SECOND };
}

/**
 * The story the token tells with the current switches: one failing test that gets repaired
 * (if auto-fix is on), then a passing one. Steps match the list in createAndTest mode:
 * 0 generate, 1 sync, 2 test run, 3 repair loop, 4 result/template. Empty in "create" mode.
 */
export function buildTimeline({ mode, autoFix, autoPromote }: FlowOptions): FlowLeg[] {
  if (mode === 'create') return [];
  const legs = [dwell('generate', 0), travel('sync', 'generate', 'test', 1), dwell('test', 2)];
  if (autoFix) {
    legs.push(
      travel('fail', 'test', 'repair', 3), dwell('repair', 3),
      travel('retry', 'repair', 'test', 3), dwell('test', 2),
    );
  }
  legs.push(travel('pass', 'test', 'result', 4), dwell('result', 4));
  if (autoPromote) legs.push(travel('promote', 'result', 'template', 4), dwell('template', 4));
  return legs;
}

export function timelineDuration(legs: readonly FlowLeg[]): number {
  return legs.reduce((sum, leg) => sum + leg.seconds, 0);
}

export interface FlowFrame {
  part: FlowNodeId | FlowEdgeId;
  step: number;
  x: number;
  y: number;
  /** True while the token dwells in a node (it is hidden under the node then). */
  inNode: boolean;
}

function pointAlong(points: readonly Point[], fraction: number): Point {
  const first = points[0] ?? [0, 0];
  let remaining = pathLength(points) * fraction;
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1] ?? first;
    const b = points[i] ?? a;
    const seg = Math.hypot(b[0] - a[0], b[1] - a[1]);
    if (remaining <= seg && seg > 0) {
      const f = remaining / seg;
      return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
    }
    remaining -= seg;
  }
  return points[points.length - 1] ?? first;
}

/** Where the token is and which step is current at `seconds` into the timeline. */
export function frameAt(legs: readonly FlowLeg[], seconds: number): FlowFrame | null {
  let elapsed = 0;
  for (const leg of legs) {
    if (seconds < elapsed + leg.seconds || leg === legs[legs.length - 1]) {
      const fraction = Math.min(1, Math.max(0, (seconds - elapsed) / leg.seconds));
      const [x, y] = pointAlong(leg.points, fraction);
      return { part: leg.part, step: leg.step, x, y, inNode: leg.points.length === 1 };
    }
    elapsed += leg.seconds;
  }
  return null;
}
