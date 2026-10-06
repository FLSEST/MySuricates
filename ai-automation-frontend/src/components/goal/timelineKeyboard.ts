import {
  pointerWithin, type Active, type ClientRect, type CollisionDetection, type KeyboardCodes,
  type KeyboardCoordinateGetter, type UniqueIdentifier,
} from '@dnd-kit/core';
import { CHIP_ANCHOR, xOfDay, type TimelineAxis } from './timelineLayout';

/**
 * Dragging by keyboard on the timeline (TODO ff6f08fd): Space lifts a TODO - Enter stays with the
 * menu -, ← → move by one day, ↑ ↓ to the row above or below, Space or Enter
 * drops, Escape and Tab cancel. Surfaces of other projects do not accept it; the live row
 * says so. Bars and diamonds (c60bf914) are moved directly by the arrow keys, without lifting.
 */
export const TIMELINE_KEYBOARD_CODES: KeyboardCodes = {
  start: ['Space'],
  cancel: ['Escape', 'Tab'],
  end: ['Space', 'Enter'],
};

/**
 * What a chip or diamond carries when lifted: where the point that holds the date sits (px from the
 * left edge), whether the chip is mirrored and what is being dragged.
 */
export interface ChipDragData {
  anchor: number;
  flipped: boolean;
  kind: 'todo' | 'milestone' | 'package';
  /** A narrow chip made from a row label - so the drag image stays narrow. */
  compact?: boolean;
}

export function chipDragData(active: Pick<Active, 'data'> | null | undefined): ChipDragData {
  const data = active?.data.current as Partial<ChipDragData> | undefined;
  return {
    anchor: data?.anchor ?? CHIP_ANCHOR, flipped: data?.flipped ?? false, kind: data?.kind ?? 'todo',
    ...(data?.compact ? { compact: true } : {}),
  };
}

/** Surfaces with days: package rows, the band header rows and the milestone row. */
const isRow = (id: UniqueIdentifier) =>
  String(id).startsWith('row:') || String(id).startsWith('strip:') || String(id) === 'milestones';
const middleOf = (rect: ClientRect) => rect.top + rect.height / 2;

/**
 * With the mouse the row under the pointer counts. By keyboard there is no pointer: then the row
 * in which the status dot of the lifted chip sits.
 */
export const timelineCollision: CollisionDetection = (args) => {
  if (args.pointerCoordinates) return pointerWithin(args);
  const { collisionRect } = args;
  const dot = { x: collisionRect.left + chipDragData(args.active).anchor, y: middleOf(collisionRect) };
  return pointerWithin({ ...args, pointerCoordinates: dot });
};

/**
 * Where the arrow keys move the lifted chip: ← → exactly one day, not beyond the axis
 * and only within one row; ↑ ↓ into the middle of the next surface. Whoever switches from a surface without days
 * (such as a row label) into a row lands on today.
 */
export function timelineKeyboardCoordinates(axis: TimelineAxis, width: number): KeyboardCoordinateGetter {
  return (event, { context }) => {
    const rect = context.collisionRect;
    if (!rect) return undefined;
    const dotX = rect.left + chipDragData(context.active).anchor;
    const over = context.over && isRow(context.over.id) ? context.over : null;
    const moveTo = (x: number, top = rect.top) => ({ x: rect.left + x - dotX, y: top });

    if (event.code === 'ArrowLeft' || event.code === 'ArrowRight') {
      if (!over) return undefined;
      const first = over.rect.left + xOfDay(axis, axis.startDay, width);
      const last = over.rect.left + xOfDay(axis, axis.endDay, width);
      const step = (event.code === 'ArrowRight' ? 1 : -1) * (width / axis.days);
      const next = Math.min(last, Math.max(first, dotX + step));
      return Math.abs(next - dotX) < 0.5 ? undefined : moveTo(next);
    }

    if (event.code === 'ArrowUp' || event.code === 'ArrowDown') {
      const down = event.code === 'ArrowDown';
      const middle = middleOf(rect);
      let target: { id: UniqueIdentifier; rect: ClientRect } | null = null;
      for (const [id, candidate] of context.droppableRects) {
        const center = middleOf(candidate);
        if (down ? center <= middle + 1 : center >= middle - 1) continue;
        if (!target || (down ? center < middleOf(target.rect) : center > middleOf(target.rect))) {
          target = { id, rect: candidate };
        }
      }
      if (!target) return undefined;
      const top = middleOf(target.rect) - rect.height / 2;
      if (!isRow(target.id)) return moveTo(dotX, top);
      const onAxis = dotX >= target.rect.left && dotX <= target.rect.left + width;
      return moveTo(over && onAxis ? dotX : target.rect.left + xOfDay(axis, axis.today, width), top);
    }

    return undefined;
  };
}
