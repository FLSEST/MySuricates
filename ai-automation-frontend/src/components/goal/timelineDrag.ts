import { createContext, useContext } from 'react';
import { dropTargetOf, milestoneAccepts, todoAccepts } from './timelineLayout';
import type { GoalMilestone, GoalTodoItem } from '../../types';

/** Was gerade am Zeitstrahl gezogen wird: ein TODO-Chip oder ein Teilziel (Raute bzw. Eintrag der Leiste). */
export type Dragged =
  | { kind: 'todo'; todo: GoalTodoItem }
  | { kind: 'milestone'; milestone: GoalMilestone }
  | null;

/** Die Flächen fragen hier, ob sie das Gezogene annehmen – nur die leuchten auf, der Rest ist abgeschaltet. */
export const TimelineDragContext = createContext<Dragged>(null);

export function useDragged(): Dragged {
  return useContext(TimelineDragContext);
}

/** Nimmt die Fläche `dropId` das Gezogene an? null, solange nichts gezogen wird. */
export function acceptsDrop(dragged: Dragged, dropId: string): boolean | null {
  if (!dragged) return null;
  const target = dropTargetOf(dropId);
  if (!target) return false;
  return dragged.kind === 'todo' ? todoAccepts(dragged.todo, target) : milestoneAccepts(dragged.milestone, target);
}

/** Tailwind-Klassen einer Fläche beim Ziehen: darüber kräftig, annehmend zart getönt, abweisend blass. */
export function dropTone(accepts: boolean | null, isOver: boolean): string {
  if (accepts === null) return '';
  if (!accepts) return 'opacity-50';
  return isOver ? 'bg-accent/15' : 'bg-accent/5';
}
