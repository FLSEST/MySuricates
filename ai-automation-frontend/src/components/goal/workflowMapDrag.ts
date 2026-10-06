import { createContext, useContext } from 'react';
import {
  closestCenter, pointerWithin, type CollisionDetection, type Modifier,
} from '@dnd-kit/core';
import { getEventCoordinates } from '@dnd-kit/utilities';
import { parseTarget, type DraggedWorkflow, type MapContext, type Verdict } from './workflowMap';

/**
 * Current drag of the workflow assignment (52bc1e8c): what is dragged, over what, and what the map knows about
 * its targets - so that only fitting targets light up.
 */
export interface WorkflowMapDragValue {
  dragged: DraggedWorkflow | null;
  overId: string | null;
  context: MapContext;
  /** Verdict for a target during the current drag; `null` while nothing is dragged. */
  judge: (targetId: string) => Verdict | null;
}

export const WorkflowMapDragContext = createContext<WorkflowMapDragValue | null>(null);

export const useWorkflowMapDrag = () => useContext(WorkflowMapDragContext);

/**
 * Which target applies: with the mouse the one under the pointer - a TODO before its package, which
 * encloses it; with the keyboard (without a pointer) the nearest one.
 */
export const workflowMapCollision: CollisionDetection = (args) => {
  if (!args.pointerCoordinates) return closestCenter(args);
  const hits = pointerWithin(args);
  const rank = (id: string | number) => {
    const target = parseTarget(id);
    return target?.kind === 'todo' ? 0 : target?.kind === 'package' ? 1 : 2;
  };
  return [...hits].sort((a, b) => rank(a.id) - rank(b.id));
};

/** Classes of a drop surface while dragging: fitting tinted, above it stronger, unsuitable faded. */
export function dropToneOf(verdict: Verdict | null, over: boolean): { fill: string; stroke: string; opacity: number } | null {
  if (verdict === null) return null;
  if (verdict !== 'ok') return { fill: 'transparent', stroke: 'transparent', opacity: over ? 0.5 : 0.45 };
  return over
    ? { fill: 'var(--color-accent-subtle)', stroke: 'var(--color-accent)', opacity: 1 }
    : { fill: 'transparent', stroke: 'var(--color-accent)', opacity: 1 };
}

/** Offset of the dragged card from the pointer: below right, so the target underneath stays visible. */
export const CURSOR_GAP = { x: 16, y: 14 };

/**
 * Places the dragged card with its "where to" row next to the pointer instead of where it was grabbed -
 * otherwise it covers exactly the TODO it is over. Only for the image (`DragOverlay`); which target
 * applies is still decided by the pointer. With the keyboard (no pointer) it stays at the drag's offset.
 */
export const besideCursor: Modifier = ({ activatorEvent, draggingNodeRect, transform }) => {
  const at = activatorEvent ? getEventCoordinates(activatorEvent) : null;
  if (!at || !draggingNodeRect) return transform;
  return {
    ...transform,
    x: transform.x + at.x - draggingNodeRect.left + CURSOR_GAP.x,
    y: transform.y + at.y - draggingNodeRect.top + CURSOR_GAP.y,
  };
};
