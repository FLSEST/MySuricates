import { closestCenter, pointerWithin, type ClientRect, type CollisionDetection } from '@dnd-kit/core';
import type { SortingStrategy } from '@dnd-kit/sortable';

/**
 * Collision detection for the widget grid. dnd-kit's closestCenter compares the centre of the
 * dragged tile with the centres of the other tiles. With tiles of very different sizes and the
 * handle in a corner, that centre is far away from the pointer, so a drop lands on a neighbour or
 * on the dragged tile itself (no move) — e.g. a wide tile dropped onto a stat tile. Here the tile
 * under the pointer wins; in the gap between tiles the tile whose centre is closest to the pointer;
 * without a pointer (keyboard dragging) closestCenter as before.
 */
export const widgetCollisionDetection: CollisionDetection = (args) => {
  const underPointer = pointerWithin(args);
  if (underPointer.length > 0) return underPointer;
  const pointer = args.pointerCoordinates;
  if (!pointer) return closestCenter(args);
  const atPointer: ClientRect = { left: pointer.x, right: pointer.x, top: pointer.y, bottom: pointer.y, width: 0, height: 0 };
  return closestCenter({ ...args, collisionRect: atPointer });
};

/**
 * Tiles stay where they are while one is dragged; the slot under it is highlighted instead.
 * rectSortingStrategy slides tiles into each other's slots, which only lines up when all tiles have
 * the same size — with mixed sizes they overlap and no longer match the drop targets.
 */
export const staticSortingStrategy: SortingStrategy = () => null;

/** Below this travel (px), a drag end is treated as a slip, not an intentional move. */
const MOVE_THRESHOLD_PX = 16;

/**
 * Whether a drag end should reorder the layout. The edit toolbar (and its grip handle) sits at
 * `-top-3`, partly outside the dragged slot's border box, so pressing its upper part puts the
 * pointer outside the tile: `pointerWithin` finds nothing and the `closestCenter` fallback can
 * already pick a neighbouring tile a few pixels past the 5px activation distance. Without this
 * threshold, a tiny slip on the handle silently reorders the layout and marks it dirty.
 */
export function shouldMove(
  active: { id: string | number },
  over: { id: string | number } | null | undefined,
  delta: { x: number; y: number },
): boolean {
  if (!over || active.id === over.id) return false;
  return Math.hypot(delta.x, delta.y) >= MOVE_THRESHOLD_PX;
}
