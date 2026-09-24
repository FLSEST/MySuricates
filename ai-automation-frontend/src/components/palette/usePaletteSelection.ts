import { useEffect, useRef, useState } from 'react';
import type { PaletteItem } from './contexts/types';

/** What the palette's keys trigger; the selection itself stays inside the hook. */
export interface PaletteKeyActions {
  close: () => void;
  cycleContext: (direction: 1 | -1) => void;
  /** Enter and Alt+1–9: run the row. */
  enter: (item: PaletteItem) => void;
  /** Shift+Enter: open the row's page. */
  open: (item: PaletteItem) => void;
  /** Cmd/Ctrl+Enter. */
  openInNewTab: (item: PaletteItem) => void;
  askAssistant: () => void;
  /** Alt+<shortcut> on the selected row, e.g. Alt+R = re-run in the run context. */
  altAction?: { shortcut: string; run: (item: PaletteItem) => void };
  /** While the variable form is open only Escape counts, and it closes the form. */
  collecting: boolean;
  cancelCollecting: () => void;
}

interface PaletteSelectionOptions {
  /** Empty query: the pinned rows, then the recent ones. Otherwise the results, then the assistant row. */
  isEmptyQuery: boolean;
  pinnedItems: PaletteItem[];
  recentItems: PaletteItem[];
  results: PaletteItem[];
  hasAssistantRow: boolean;
  keys: PaletteKeyActions;
}

/**
 * Selected row of the command palette and its keyboard control on the window, so the keys work
 * while the search field has the focus: ↑/↓ move, Enter runs (Shift opens, Cmd/Ctrl opens in a
 * new tab), Alt+1–9 runs row N, Tab/Shift+Tab switches the context, Escape closes.
 */
export function usePaletteSelection({
  isEmptyQuery, pinnedItems, recentItems, results, hasAssistantRow, keys,
}: PaletteSelectionOptions) {
  const [selectedIdx, setSelectedIdx] = useState(0);

  const assistantIdx = hasAssistantRow ? results.length : -1;
  const totalItems = isEmptyQuery
    ? pinnedItems.length + recentItems.length
    : results.length + (hasAssistantRow ? 1 : 0);

  const itemAt = (idx: number): PaletteItem | null => {
    if (isEmptyQuery) {
      if (idx < pinnedItems.length) return pinnedItems[idx] ?? null;
      return recentItems[idx - pinnedItems.length] ?? null;
    }
    return results[idx] ?? null;
  };

  const handleKey = (e: KeyboardEvent) => {
    if (keys.collecting) {
      if (e.key === 'Escape') {
        e.preventDefault();
        keys.cancelCollecting();
      }
      return;
    }
    if (e.key === 'Escape') {
      e.preventDefault();
      keys.close();
      return;
    }
    if (e.key === 'Tab') {
      e.preventDefault();
      keys.cycleContext(e.shiftKey ? -1 : 1);
      return;
    }
    if (e.altKey && /^[1-9]$/.test(e.key)) {
      // Alt+N jumps directly to row N (1-indexed): the pinned and recent rows for an empty
      // query, otherwise the result rows and the trailing assistant row.
      e.preventDefault();
      const n = parseInt(e.key, 10) - 1;
      if (!isEmptyQuery && n === assistantIdx) {
        setSelectedIdx(n);
        keys.askAssistant();
        return;
      }
      const item = itemAt(n);
      if (item) {
        setSelectedIdx(n);
        keys.enter(item);
      }
      return;
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setSelectedIdx((i) => Math.min(i + 1, Math.max(0, totalItems - 1)));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setSelectedIdx((i) => Math.max(0, i - 1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (!isEmptyQuery && selectedIdx === assistantIdx) {
        keys.askAssistant();
        return;
      }
      const item = itemAt(selectedIdx);
      if (item) {
        if (e.metaKey || e.ctrlKey) keys.openInNewTab(item);
        else if (e.shiftKey) keys.open(item);
        else keys.enter(item);
      }
    } else if (keys.altAction && e.altKey && e.key.toLowerCase() === keys.altAction.shortcut) {
      // Alt+<shortcut> fires even when the search input is focused
      e.preventDefault();
      const item = itemAt(selectedIdx);
      if (item) keys.altAction.run(item);
    }
  };

  // The window listener stays for the palette's lifetime and always calls the handler of the
  // latest render — it sees the current rows, selection and query without re-subscribing.
  const handleKeyRef = useRef(handleKey);
  useEffect(() => {
    handleKeyRef.current = handleKey;
  });
  useEffect(() => {
    const listener = (e: KeyboardEvent) => handleKeyRef.current(e);
    window.addEventListener('keydown', listener);
    return () => window.removeEventListener('keydown', listener);
  }, []);

  return { selectedIdx, select: setSelectedIdx, itemAt, assistantIdx };
}
