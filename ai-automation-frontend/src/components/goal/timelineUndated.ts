import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import type { TimelineBand } from './timelineLayout';

/**
 * TODOs ohne Datum im Zeitstrahl (85889063, Flo: „Diese 8 ohne Datum, die sind ziemlich versteckt
 * so“). Je Zeile ein deutlicher Knopf mit Zähler; Zeilen mit höchstens fünf solchen TODOs stehen
 * offen, größere eingeklappt. Oben am Zeitstrahl klappt ein Schalter alle auf oder zu – das merkt
 * sich der Browser je Ziel. Einzelne Zeilen lassen sich danach wieder für sich umschalten.
 */

/** Bis zu so vielen TODOs ohne Datum steht eine Zeile von selbst offen. */
export const UNDATED_OPEN_MAX = 5;

const STORAGE_KEY = 'mysuricates-goal-undated';

/** `all`: alle offen, `none`: alle zu; ohne Eintrag gilt die Regel „höchstens fünf“. */
export type UndatedMode = 'all' | 'none';

function readModes(): Record<string, UndatedMode> {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}') as Record<string, UndatedMode>;
  } catch {
    return {};
  }
}

function storeMode(goalId: string, mode: UndatedMode) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...readModes(), [goalId]: mode }));
  } catch {
    // privates Fenster: gilt bis zum Neuladen
  }
}

const defaultOpen = (mode: UndatedMode | null, count: number) =>
  mode === 'all' ? true : mode === 'none' ? false : count <= UNDATED_OPEN_MAX;

export interface UndatedValue {
  /** Ist die Liste der Zeile offen? `rowId`: `<band>:<zeile>`. */
  isOpen: (rowId: string, count: number) => boolean;
  setOpen: (rowId: string, open: boolean) => void;
  /** Welches Paket der Leiste „Ohne Zeitraum“ seine TODOs ohne Datum zeigt (je Band eins). */
  pill: (band: string) => string | null;
  setPill: (band: string, workPackageId: string | null) => void;
}

export const UndatedContext = createContext<UndatedValue | null>(null);

/** Zustand des Zeitstrahls: Modus je Ziel (gespeichert), Abweichungen einzelner Zeilen (nur bis zum Verlassen). */
export function useUndatedDisclosure(goalId: string) {
  const [mode, setModeState] = useState<UndatedMode | null>(() => readModes()[goalId] ?? null);
  const [rows, setRows] = useState<Record<string, boolean>>({});
  const [pills, setPills] = useState<Record<string, string | null>>({});
  const isOpen = useCallback((rowId: string, count: number) => rows[rowId] ?? defaultOpen(mode, count), [rows, mode]);
  const setOpen = useCallback((rowId: string, open: boolean) => setRows((current) => ({ ...current, [rowId]: open })), []);
  const pill = useCallback((band: string) => pills[band] ?? null, [pills]);
  const setPill = useCallback((band: string, workPackageId: string | null) =>
    setPills((current) => ({ ...current, [band]: workPackageId })), []);
  const setMode = useCallback((next: UndatedMode) => {
    setModeState(next);
    setRows({});
    storeMode(goalId, next);
  }, [goalId]);
  const value = useMemo<UndatedValue>(() => ({ isOpen, setOpen, pill, setPill }), [isOpen, setOpen, pill, setPill]);
  return { value, setMode };
}

/** Offen/zu einer Zeile – aus dem Zeitstrahl, ohne ihn (einzeln gerendert) nach der Regel „höchstens fünf“. */
export function useUndatedRow(rowId: string, count: number): [boolean, (open: boolean) => void] {
  const shared = useContext(UndatedContext);
  const [local, setLocal] = useState<boolean | null>(null);
  if (shared) return [shared.isOpen(rowId, count), (open) => shared.setOpen(rowId, open)];
  return [local ?? defaultOpen(null, count), setLocal];
}

/** Kennung einer Zeile für den Zustand: Band und Zeile. */
export const undatedRowId = (band: string, row: string) => `${band}:${row}`;

/** Alle Zeilen mit TODOs ohne Datum – für den Schalter oben und für „Zeigen“. */
export function undatedRows(bands: TimelineBand[]): { rowId: string; todoIds: string[] }[] {
  return bands.flatMap((band) => [...band.rows, band.noPackage]
    .filter((row) => row.undated.length > 0)
    .map((row) => ({ rowId: undatedRowId(band.key, row.key), todoIds: row.undated.map((todo) => todo.id) })));
}
