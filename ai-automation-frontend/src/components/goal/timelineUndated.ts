import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import type { TimelineBand } from './timelineLayout';

/**
 * TODOs without a date on the timeline (85889063, Flo: "These 8 without a date, they are pretty hidden
 * like this"). Each row gets a clear button with a counter; rows with at most five such TODOs are
 * open, larger ones collapsed. At the top of the timeline a switch opens or closes all - the browser
 * remembers that per goal. Individual rows can then be toggled again on their own.
 */

/** Up to this many TODOs without a date, a row is open by itself. */
export const UNDATED_OPEN_MAX = 5;

const STORAGE_KEY = 'mysuricates-goal-undated';

/** `all`: all open, `none`: all closed; without an entry the rule "at most five" applies. */
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
  /** Is the row's list open? `rowId`: `<band>:<row>`. */
  isOpen: (rowId: string, count: number) => boolean;
  setOpen: (rowId: string, open: boolean) => void;
  /** Which package of the "Ohne Zeitraum" strip shows its TODOs without a date (one per band). */
  pill: (band: string) => string | null;
  setPill: (band: string, workPackageId: string | null) => void;
}

export const UndatedContext = createContext<UndatedValue | null>(null);

/** State of the timeline: mode per goal (stored), deviations of individual rows (only until leaving). */
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

/** Open/closed of a row - from the timeline; without it (rendered individually) by the rule "at most five". */
export function useUndatedRow(rowId: string, count: number): [boolean, (open: boolean) => void] {
  const shared = useContext(UndatedContext);
  const [local, setLocal] = useState<boolean | null>(null);
  if (shared) return [shared.isOpen(rowId, count), (open) => shared.setOpen(rowId, open)];
  return [local ?? defaultOpen(null, count), setLocal];
}

/** Identifier of a row for the state: band and row. */
export const undatedRowId = (band: string, row: string) => `${band}:${row}`;

/** All rows with TODOs without a date - for the switch at the top and for "Zeigen". */
export function undatedRows(bands: TimelineBand[]): { rowId: string; todoIds: string[] }[] {
  return bands.flatMap((band) => [...band.rows, band.noPackage]
    .filter((row) => row.undated.length > 0)
    .map((row) => ({ rowId: undatedRowId(band.key, row.key), todoIds: row.undated.map((todo) => todo.id) })));
}
