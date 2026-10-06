import { createContext, useContext } from 'react';
import type { DraggableSyntheticListeners } from '@dnd-kit/core';
import {
  bandOfPackage, bandOfTodo, dropTargetOf, milestoneAccepts, packageAccepts, todoAccepts, type DayRange, type DropTarget,
} from './timelineLayout';
import type { GoalMilestone, GoalTodoItem, GoalWorkPackage } from '../../types';

/**
 * What is currently being dragged on the timeline: a TODO chip, a work package - moved as a bar
 * (`move`), scheduled from the strip (`schedule`) or dragged at one end (`start`/`end`) - or
 * ein Meilenstein.
 */
export type Dragged =
  | { kind: 'todo'; todo: GoalTodoItem }
  | { kind: 'package'; workPackage: GoalWorkPackage; mode: 'move' | 'schedule' | 'start' | 'end' }
  | { kind: 'milestone'; milestone: GoalMilestone }
  | null;

/** The row below the dragged item: text and whether it may land here (otherwise the text says why not). */
export interface LiveLine {
  text: string;
  refused: boolean;
  /** With an arrow in front: where it goes ("→ Repo sauber · …") - not for periods and days. */
  arrow?: boolean;
}

/** What the surfaces need to know while dragging: the dragged item, where a package would currently land, the live row. */
export interface DragState {
  dragged: Dragged;
  /** Preview of the period of a dragged package (move, schedule, resize). */
  preview: { workPackageId: string; range: DayRange | null } | null;
  live: LiveLine | null;
}

export const IDLE: DragState = { dragged: null, preview: null, live: null };

/** The surfaces ask here whether they accept the dragged item - those light up, the others fade. */
export const TimelineDragContext = createContext<DragState>(IDLE);

export function useDragState(): DragState {
  return useContext(TimelineDragContext);
}

/** Whether `target` accepts the dragged item. A bar dragged at one end needs no surface. */
export function acceptsTarget(dragged: NonNullable<Dragged>, target: DropTarget): boolean {
  switch (dragged.kind) {
    case 'todo': return todoAccepts(dragged.todo, target);
    case 'milestone': return milestoneAccepts(target);
    default: return dragged.mode === 'start' || dragged.mode === 'end' ? true : packageAccepts(dragged.workPackage, target);
  }
}

/** Which surfaces count for the dragged item - only those light up or fade, the others stay calm. */
const RELEVANT: Record<'todo' | 'package' | 'milestone', DropTarget['kind'][]> = {
  todo: ['row', 'undated', 'pill'],
  package: ['row', 'strip', 'tray'],
  // A diamond may go on any day - in its row, in every package row and in every header row (c60bf914)
  milestone: ['milestones', 'row', 'strip'],
};

/**
 * How a surface looks while dragging: `true` accepts (tinted), `false` rejects (faded), `null`
 * stays calm - while nothing is dragged, for surfaces that play no role for the dragged item,
 * and when dragging at a bar end (that needs no surface).
 */
export function acceptsDrop(dragged: Dragged, dropId: string): boolean | null {
  if (!dragged) return null;
  if (dragged.kind === 'package' && (dragged.mode === 'start' || dragged.mode === 'end')) return null;
  const target = dropTargetOf(dropId);
  if (!target || !RELEVANT[dragged.kind].includes(target.kind)) return null;
  return acceptsTarget(dragged, target);
}

/** The band the dragged item belongs to; null for milestones (they belong to the whole goal). */
export function bandOfDragged(dragged: Dragged): string | null {
  if (!dragged || dragged.kind === 'milestone') return null;
  return dragged.kind === 'todo' ? bandOfTodo(dragged.todo) : bandOfPackage(dragged.workPackage);
}

/** Tailwind classes of a surface while dragging: accepting lightly tinted (stronger above it), rejecting faded. */
export function dropTone(accepts: boolean | null, isOver: boolean): string {
  if (accepts === null) return '';
  if (!accepts) return isOver ? 'opacity-60 bg-danger/10' : 'opacity-50';
  return isOver ? 'bg-accent/15' : 'bg-accent/5';
}

/** Only the pointer activators of dnd-kit: bars, handles and diamonds are moved directly by the keyboard, not by lifting. */
export function pointerOnly(listeners: DraggableSyntheticListeners): DraggableSyntheticListeners {
  if (!listeners) return listeners;
  const { onKeyDown: _keyboard, ...pointer } = listeners;
  return pointer;
}
