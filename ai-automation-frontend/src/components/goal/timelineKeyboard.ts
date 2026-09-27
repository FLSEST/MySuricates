import {
  pointerWithin, type Active, type ClientRect, type CollisionDetection, type KeyboardCodes,
  type KeyboardCoordinateGetter, type UniqueIdentifier,
} from '@dnd-kit/core';
import { CHIP_ANCHOR, xOfDay, type TimelineAxis } from './timelineLayout';

/**
 * Ziehen per Tastatur am Zeitstrahl (TODO ff6f08fd): Leertaste hebt ein TODO an – Enter bleibt beim
 * Menü –, ← → schieben um einen Tag, ↑ ↓ in die Zeile darüber oder darunter, Leertaste oder Enter
 * legt ab, Escape und Tab brechen ab. Flächen fremder Projekte nehmen es nicht an; die Live-Zeile
 * sagt das. Balken und Rauten (c60bf914) verschieben die Pfeiltasten direkt, ohne Anheben.
 */
export const TIMELINE_KEYBOARD_CODES: KeyboardCodes = {
  start: ['Space'],
  cancel: ['Escape', 'Tab'],
  end: ['Space', 'Enter'],
};

/**
 * Was ein Chip oder eine Raute beim Anheben mitgibt: wo der Punkt sitzt, der das Datum trägt (px vom
 * linken Rand), ob der Chip gespiegelt steht und was gezogen wird.
 */
export interface ChipDragData {
  anchor: number;
  flipped: boolean;
  kind: 'todo' | 'milestone' | 'package';
  /** Ein schmaler Chip aus der Beschriftung einer Zeile – das Bild beim Ziehen bleibt so schmal. */
  compact?: boolean;
}

export function chipDragData(active: Pick<Active, 'data'> | null | undefined): ChipDragData {
  const data = active?.data.current as Partial<ChipDragData> | undefined;
  return {
    anchor: data?.anchor ?? CHIP_ANCHOR, flipped: data?.flipped ?? false, kind: data?.kind ?? 'todo',
    ...(data?.compact ? { compact: true } : {}),
  };
}

/** Flächen mit Tagen: Paket-Zeilen, die Kopfzeilen der Bänder und die Meilenstein-Zeile. */
const isRow = (id: UniqueIdentifier) =>
  String(id).startsWith('row:') || String(id).startsWith('strip:') || String(id) === 'milestones';
const middleOf = (rect: ClientRect) => rect.top + rect.height / 2;

/**
 * Mit der Maus gilt die Zeile unter dem Zeiger. Per Tastatur gibt es keinen Zeiger: dann die Zeile,
 * in der der Statuspunkt des angehobenen Chips steht.
 */
export const timelineCollision: CollisionDetection = (args) => {
  if (args.pointerCoordinates) return pointerWithin(args);
  const { collisionRect } = args;
  const dot = { x: collisionRect.left + chipDragData(args.active).anchor, y: middleOf(collisionRect) };
  return pointerWithin({ ...args, pointerCoordinates: dot });
};

/**
 * Wohin die Pfeiltasten den angehobenen Chip schieben: ← → genau einen Tag, nicht über die Achse
 * hinaus und nur in einer Zeile; ↑ ↓ mitten in die nächste Fläche. Wer aus einer Fläche ohne Tage
 * (etwa der Beschriftung einer Zeile) in eine Zeile wechselt, landet auf heute.
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
