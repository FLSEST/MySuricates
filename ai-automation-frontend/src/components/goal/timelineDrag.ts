import { createContext, useContext } from 'react';
import type { DraggableSyntheticListeners } from '@dnd-kit/core';
import {
  bandOfPackage, bandOfTodo, dropTargetOf, milestoneAccepts, packageAccepts, todoAccepts, type DayRange, type DropTarget,
} from './timelineLayout';
import type { GoalMilestone, GoalTodoItem, GoalWorkPackage } from '../../types';

/**
 * Was gerade am Zeitstrahl gezogen wird: ein TODO-Chip, ein Arbeitspaket – als Balken verschoben
 * (`move`), aus der Leiste eingeplant (`schedule`) oder an einem Ende gezogen (`start`/`end`) – oder
 * ein Meilenstein.
 */
export type Dragged =
  | { kind: 'todo'; todo: GoalTodoItem }
  | { kind: 'package'; workPackage: GoalWorkPackage; mode: 'move' | 'schedule' | 'start' | 'end' }
  | { kind: 'milestone'; milestone: GoalMilestone }
  | null;

/** Die Zeile unter dem Gezogenen: Text und ob es hier landen darf (sonst sagt der Text, warum nicht). */
export interface LiveLine {
  text: string;
  refused: boolean;
  /** Mit Pfeil davor: wohin es geht („→ Repo sauber · …“) – bei Zeiträumen und Tagen nicht. */
  arrow?: boolean;
}

/** Was die Flächen beim Ziehen wissen müssen: das Gezogene, wohin ein Paket gerade käme, die Live-Zeile. */
export interface DragState {
  dragged: Dragged;
  /** Vorschau des Zeitraums eines gezogenen Pakets (verschieben, einplanen, Größe ändern). */
  preview: { workPackageId: string; range: DayRange | null } | null;
  live: LiveLine | null;
}

export const IDLE: DragState = { dragged: null, preview: null, live: null };

/** Die Flächen fragen hier, ob sie das Gezogene annehmen – die leuchten auf, die übrigen werden blass. */
export const TimelineDragContext = createContext<DragState>(IDLE);

export function useDragState(): DragState {
  return useContext(TimelineDragContext);
}

/** Ob `target` das Gezogene annimmt. Ein Balken, der an einem Ende gezogen wird, braucht keine Fläche. */
export function acceptsTarget(dragged: NonNullable<Dragged>, target: DropTarget): boolean {
  switch (dragged.kind) {
    case 'todo': return todoAccepts(dragged.todo, target);
    case 'milestone': return milestoneAccepts(target);
    default: return dragged.mode === 'start' || dragged.mode === 'end' ? true : packageAccepts(dragged.workPackage, target);
  }
}

/** Welche Flächen für das Gezogene zählen – nur die leuchten auf oder werden blass, die übrigen bleiben ruhig. */
const RELEVANT: Record<'todo' | 'package' | 'milestone', DropTarget['kind'][]> = {
  todo: ['row', 'undated', 'pill'],
  package: ['row', 'strip', 'tray'],
  milestone: ['milestones'],
};

/**
 * Wie eine Fläche beim Ziehen aussieht: `true` nimmt an (getönt), `false` weist ab (blass), `null`
 * bleibt ruhig – solange nichts gezogen wird, für Flächen, die für das Gezogene keine Rolle spielen,
 * und beim Ziehen an einem Balkenende (das braucht keine Fläche).
 */
export function acceptsDrop(dragged: Dragged, dropId: string): boolean | null {
  if (!dragged) return null;
  if (dragged.kind === 'package' && (dragged.mode === 'start' || dragged.mode === 'end')) return null;
  const target = dropTargetOf(dropId);
  if (!target || !RELEVANT[dragged.kind].includes(target.kind)) return null;
  return acceptsTarget(dragged, target);
}

/** Das Band, in das das Gezogene gehört; null bei Meilensteinen (sie gehören dem ganzen Ziel). */
export function bandOfDragged(dragged: Dragged): string | null {
  if (!dragged || dragged.kind === 'milestone') return null;
  return dragged.kind === 'todo' ? bandOfTodo(dragged.todo) : bandOfPackage(dragged.workPackage);
}

/** Tailwind-Klassen einer Fläche beim Ziehen: annehmend zart getönt (darüber kräftiger), abweisend blass. */
export function dropTone(accepts: boolean | null, isOver: boolean): string {
  if (accepts === null) return '';
  if (!accepts) return isOver ? 'opacity-60 bg-danger/10' : 'opacity-50';
  return isOver ? 'bg-accent/15' : 'bg-accent/5';
}

/** Nur die Zeiger-Auslöser von dnd-kit: Balken, Griffe und Rauten bewegt die Tastatur direkt, nicht per Anheben. */
export function pointerOnly(listeners: DraggableSyntheticListeners): DraggableSyntheticListeners {
  if (!listeners) return listeners;
  const { onKeyDown: _keyboard, ...pointer } = listeners;
  return pointer;
}
