import { createContext, useContext } from 'react';
import {
  closestCenter, pointerWithin, type CollisionDetection, type Modifier,
} from '@dnd-kit/core';
import { getEventCoordinates } from '@dnd-kit/utilities';
import { parseTarget, type DraggedWorkflow, type MapContext, type Verdict } from './workflowMap';

/**
 * Laufender Zug der Workflow-Zuordnung (52bc1e8c): was gezogen wird, worüber, und was die Karte über
 * ihre Ziele weiß – damit nur passende Ziele aufleuchten.
 */
export interface WorkflowMapDragValue {
  dragged: DraggedWorkflow | null;
  overId: string | null;
  context: MapContext;
  /** Urteil für ein Ziel beim laufenden Zug; `null`, solange nichts gezogen wird. */
  judge: (targetId: string) => Verdict | null;
}

export const WorkflowMapDragContext = createContext<WorkflowMapDragValue | null>(null);

export const useWorkflowMapDrag = () => useContext(WorkflowMapDragContext);

/**
 * Welches Ziel gilt: mit der Maus das, über dem der Zeiger steht – ein TODO vor seinem Paket, das
 * es umschließt; mit der Tastatur (ohne Zeiger) das nächstgelegene.
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

/** Klassen einer Ablagefläche beim Ziehen: passend getönt, darüber kräftiger, ungeeignet blass. */
export function dropToneOf(verdict: Verdict | null, over: boolean): { fill: string; stroke: string; opacity: number } | null {
  if (verdict === null) return null;
  if (verdict !== 'ok') return { fill: 'transparent', stroke: 'transparent', opacity: over ? 0.5 : 0.45 };
  return over
    ? { fill: 'var(--color-accent-subtle)', stroke: 'var(--color-accent)', opacity: 1 }
    : { fill: 'transparent', stroke: 'var(--color-accent)', opacity: 1 };
}

/** Abstand der gezogenen Karte zum Zeiger: rechts unterhalb, damit das Ziel darunter sichtbar bleibt. */
export const CURSOR_GAP = { x: 16, y: 14 };

/**
 * Setzt die gezogene Karte samt Zeile „wohin“ neben den Zeiger statt dorthin, wo sie gegriffen wurde –
 * sonst deckt sie genau das TODO ab, über dem sie steht. Nur für das Bild (`DragOverlay`); welches Ziel
 * gilt, entscheidet weiter der Zeiger. Mit der Tastatur (kein Zeiger) bleibt es beim Versatz des Zuges.
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
