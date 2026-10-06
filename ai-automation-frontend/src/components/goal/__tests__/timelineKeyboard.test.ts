import { describe, it, expect } from 'vitest';
import type { ClientRect, Collision, SensorContext, UniqueIdentifier } from '@dnd-kit/core';
import { CHIP_ANCHOR, CHIP_WIDTH, buildAxis, dayOfIsoDate, xOfDay } from '../timelineLayout';
import { chipDragData, timelineCollision, timelineKeyboardCoordinates } from '../timelineKeyboard';

const W = 600;
const today = dayOfIsoDate('2026-09-24')!;
// 30 days of 20 px: today is day 3 of the axis
const axis = { ...buildAxis({ today, days: [today + 20], target: null }, W), startDay: today - 3, endDay: today + 26, days: 30 };
const DAY = W / axis.days;

const rect = (left: number, top: number, width: number, height: number): ClientRect =>
  ({ left, top, width, height, right: left + width, bottom: top + height });

/** Rows from x = 180 (right of the label), below them the bar „ungeplant“ across the whole width. */
const rows = new Map<UniqueIdentifier, ClientRect>([
  ['row:m1', rect(180, 100, W, 48)],
  ['row:none', rect(180, 148, W, 48)],
  ['unplanned', rect(60, 220, W + 120, 80)],
]);

/** The lifted chip: dot `anchor` px from its left edge, middle vertically at `middle`. */
function context(dotX: number, middle: number, over: UniqueIdentifier | null, anchor = CHIP_ANCHOR): SensorContext {
  return {
    active: { id: 'todo:a', data: { current: { anchor, flipped: false } }, rect: { current: { initial: null, translated: null } } },
    collisionRect: rect(dotX - anchor, middle - 14, CHIP_WIDTH, 28),
    over: over === null ? null : { id: over, rect: rows.get(over)!, disabled: false, data: { current: undefined } },
    droppableRects: rows,
  } as unknown as SensorContext;
}

const move = (code: string, ctx: SensorContext) =>
  timelineKeyboardCoordinates(axis, W)(new KeyboardEvent('keydown', { code }), {
    active: 'todo:a', context: ctx, currentCoordinates: { x: ctx.collisionRect!.left, y: ctx.collisionRect!.top },
  });
const dayX = (day: number) => 180 + xOfDay(axis, day, W);

describe('timelineKeyboardCoordinates (ff6f08fd)', () => {
  it('moves the lifted chip by exactly one day to the right and left inside a row', () => {
    const start = dayX(today + 5);
    expect(move('ArrowRight', context(start, 124, 'row:m1'))).toEqual({ x: start + DAY - CHIP_ANCHOR, y: 110 });
    expect(move('ArrowLeft', context(start, 124, 'row:m1'))).toEqual({ x: start - DAY - CHIP_ANCHOR, y: 110 });
  });

  it('keeps the dot on its day for a flipped chip, and stops at the ends of the axis', () => {
    const anchor = CHIP_WIDTH - CHIP_ANCHOR;
    const last = dayX(axis.endDay);
    expect(move('ArrowLeft', context(last, 124, 'row:none', anchor))).toEqual({ x: last - DAY - anchor, y: 110 });
    expect(move('ArrowRight', context(last, 124, 'row:none', anchor))).toBeUndefined();
    expect(move('ArrowLeft', context(dayX(axis.startDay), 124, 'row:none'))).toBeUndefined();
  });

  // c60bf914: a diamond moves by days in the head strip of its band just like a chip in a row
  it('moves a lifted diamond by one day in the head strip of its band', () => {
    const start = dayX(today + 5);
    const strip = rect(180, 64, W, 36);
    const ctx = {
      ...context(start, 82, null, 10),
      over: { id: 'strip:p1', rect: strip, disabled: false, data: { current: undefined } },
    } as unknown as SensorContext;
    expect(move('ArrowRight', ctx)).toEqual({ x: start + DAY - 10, y: 82 - 14 });
  });

  it('moves days only in a row, not in "unplanned"', () => {
    expect(move('ArrowRight', context(100, 260, 'unplanned'))).toBeUndefined();
  });

  it('steps into the row below or above, keeping the day, and from a row down into "unplanned"', () => {
    const start = dayX(today + 5);
    expect(move('ArrowDown', context(start, 124, 'row:m1'))).toEqual({ x: start - CHIP_ANCHOR, y: 172 - 14 });
    expect(move('ArrowUp', context(start, 172, 'row:none'))).toEqual({ x: start - CHIP_ANCHOR, y: 124 - 14 });
    expect(move('ArrowDown', context(start, 172, 'row:none'))).toEqual({ x: start - CHIP_ANCHOR, y: 260 - 14 });
    expect(move('ArrowUp', context(start, 124, 'row:m1'))).toBeUndefined();
    expect(move('ArrowDown', context(start, 260, 'unplanned'))).toBeUndefined();
  });

  it('puts a chip coming up from "unplanned" on today', () => {
    expect(move('ArrowUp', context(80, 260, 'unplanned'))).toEqual({ x: dayX(today) - CHIP_ANCHOR, y: 172 - 14 });
  });

  it('ignores other keys', () => {
    expect(move('KeyA', context(dayX(today), 124, 'row:m1'))).toBeUndefined();
  });
});

describe('timelineCollision (ff6f08fd)', () => {
  const containers = [...rows.keys()].map((id) => ({ id, key: id, data: { current: undefined }, disabled: false, node: { current: null }, rect: { current: rows.get(id)! } }));
  const hit = (collisions: Collision[]) => collisions.map((collision) => collision.id);
  const args = (dotX: number, middle: number, pointer: { x: number; y: number } | null) => ({
    active: { id: 'todo:a', data: { current: { anchor: CHIP_ANCHOR, flipped: false } }, rect: { current: { initial: null, translated: null } } },
    collisionRect: rect(dotX - CHIP_ANCHOR, middle - 14, CHIP_WIDTH, 28),
    droppableRects: rows,
    droppableContainers: containers,
    pointerCoordinates: pointer,
  }) as unknown as Parameters<typeof timelineCollision>[0];

  it('takes the row under the mouse pointer', () => {
    expect(hit(timelineCollision(args(dayX(today), 124, { x: dayX(today), y: 170 })))).toEqual(['row:none']);
  });

  it('takes the row under the dot of the lifted chip without a pointer (keyboard)', () => {
    expect(hit(timelineCollision(args(dayX(today), 124, null)))).toEqual(['row:m1']);
    expect(hit(timelineCollision(args(100, 260, null)))).toEqual(['unplanned']);
  });

  it('reads anchor, orientation and kind of the lifted item, with the plain TODO chip as fallback', () => {
    expect(chipDragData({ data: { current: { anchor: 140, flipped: true } } })).toEqual({ anchor: 140, flipped: true, kind: 'todo' });
    expect(chipDragData({ data: { current: { anchor: 10, flipped: false, kind: 'milestone' } } }))
      .toEqual({ anchor: 10, flipped: false, kind: 'milestone' });
    expect(chipDragData(null)).toEqual({ anchor: CHIP_ANCHOR, flipped: false, kind: 'todo' });
  });
});
