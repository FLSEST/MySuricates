import { createContext, useContext } from 'react';

/**
 * Was die Seitenleiste der Zielseite zeigt (c60bf914, Flo: „Beim Anklicken eines Milestones sollten
 * die TODOs und Workflows in der Ansicht rechts sein“): ein Meilenstein oder ein Arbeitspaket. Die
 * Auswahl steht in der URL (`?item=milestone:<id>` bzw. `workpackage:<id>`), damit Zurück und
 * Neuladen sie behalten.
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
  /** Öffnet die Seitenleiste; `trigger` bekommt beim Schließen den Fokus zurück. */
  select: (selection: GoalSelection, trigger?: HTMLElement | null) => void;
  close: () => void;
}

export const GoalSelectionContext = createContext<GoalSelectionValue | null>(null);

export const useGoalSelection = () => useContext(GoalSelectionContext);

/** Ist dieser Eintrag gerade in der Seitenleiste offen? */
export function useIsSelected(kind: GoalSelection['kind'], id: string): boolean {
  const selection = useGoalSelection();
  return sameSelection(selection?.selected ?? null, { kind, id });
}
