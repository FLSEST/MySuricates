import { describe, it, expect } from 'vitest';
import { closestCenter, type Active, type ClientRect, type DroppableContainer } from '@dnd-kit/core';
import { staticSortingStrategy, widgetCollisionDetection } from '../dashboardDnd';

const rect = (left: number, top: number, width: number, height: number): ClientRect => ({
  left, top, width, height, right: left + width, bottom: top + height,
});
const container = (id: string): DroppableContainer => ({
  id, key: id, data: { current: {} }, disabled: false, node: { current: null }, rect: { current: null },
});

// A wide tile (recent runs) next to two stat tiles, as in a row with mixed sizes.
const droppableRects = new Map([
  ['recent-runs', rect(0, 0, 800, 400)],
  ['stat-a', rect(820, 0, 150, 70)],
  ['stat-b', rect(820, 90, 150, 70)],
]);
const base = {
  active: { id: 'recent-runs', data: { current: {} }, rect: { current: { initial: null, translated: null } } } as Active,
  droppableRects,
  droppableContainers: [...droppableRects.keys()].map(container),
  // Dragged by its handle in the top-right corner (790/10) so that the handle sits on stat-a.
  collisionRect: rect(100, 20, 800, 400),
};

describe('widgetCollisionDetection', () => {
  it('drops onto the tile under the pointer, where the tile centres point back at the dragged tile', () => {
    const args = { ...base, pointerCoordinates: { x: 890, y: 30 } };
    // The bug: the dragged tile's centre is still closest to its own slot, so nothing moves.
    expect(closestCenter(args)[0]?.id).toBe('recent-runs');
    expect(widgetCollisionDetection(args)[0]?.id).toBe('stat-a');
  });

  it('takes the tile closest to the pointer when it is in the gap between tiles', () => {
    expect(widgetCollisionDetection({ ...base, pointerCoordinates: { x: 810, y: 60 } })[0]?.id).toBe('stat-a');
  });

  it('keeps centre-based detection for keyboard dragging, which has no pointer', () => {
    const args = { ...base, collisionRect: rect(820, 90, 800, 400), pointerCoordinates: null };
    expect(widgetCollisionDetection(args)).toEqual(closestCenter(args));
  });
});

describe('staticSortingStrategy', () => {
  it('never moves the other tiles while dragging', () => {
    expect(staticSortingStrategy({ activeNodeRect: rect(0, 0, 10, 10), activeIndex: 0, index: 1, rects: [], overIndex: 1 })).toBeNull();
  });
});
