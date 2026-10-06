import { createContext, useContext } from 'react';

/**
 * What the side bar of the goal page shows (c60bf914, Flo: „Beim Anklicken eines Milestones sollten
 * die TODOs und Workflows in der Ansicht rechts sein“): a milestone or a work package. The
 * selection is in the URL (`?item=milestone:<id>` or `workpackage:<id>`) so that Back and
 * reload keep it.
 */
export type GoalSelection = { kind: 'milestone' | 'workpackage'; id: string };

export const SELECTION_PARAM = 'item';

/** `milestone:<id>` / `workpackage:<id>` → Auswahl; alles andere → keine. */
export function parseSelection(value: string | null): GoalSelection | null {
  if (!value) return null;
  const index = value.indexOf(':');
  const kind = value.slice(0, index);
  const id = value.slice(index + 1);
  if (index < 0 || !id || (kind !== 'milestone' && kind !== 'workpackage')) return null;
  return { kind, id };
}

export const selectionValue = (selection: GoalSelection) => `${selection.kind}:${selection.id}`;

export const sameSelection = (a: GoalSelection | null, b: GoalSelection | null) =>
  !!a && !!b && a.kind === b.kind && a.id === b.id;

export interface GoalSelectionValue {
  selected: GoalSelection | null;
  /** Opens the side bar; `trigger` gets the focus back on closing. */
  select: (selection: GoalSelection, trigger?: HTMLElement | null) => void;
  close: () => void;
}

export const GoalSelectionContext = createContext<GoalSelectionValue | null>(null);

export const useGoalSelection = () => useContext(GoalSelectionContext);

/** Is this entry currently open in the side bar? */
export function useIsSelected(kind: GoalSelection['kind'], id: string): boolean {
  const selection = useGoalSelection();
  return sameSelection(selection?.selected ?? null, { kind, id });
}
