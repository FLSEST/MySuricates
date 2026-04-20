import { useCallback, useEffect, useState } from 'react';

const RECENTS_KEY = 'workflows-recents';
const PINS_KEY = 'workflows-pins';
const MAX_RECENTS = 10;
const MAX_PINS = 8;

function loadList(key: string): string[] {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    return [];
  }
}

function saveList(key: string, list: string[]) {
  try {
    localStorage.setItem(key, JSON.stringify(list));
  } catch {
    /* ignore quota */
  }
}

/**
 * LocalStorage-backed recent + pinned workflow IDs for the Command Palette.
 * Recents: FIFO, deduped, capped at MAX_RECENTS. Pushed when a workflow runs.
 * Pins: user-toggled via palette star icon, capped at MAX_PINS.
 * The `workflows:history-changed` event lets other palette instances (unlikely but cheap)
 * stay in sync within the same tab.
 */
export function usePaletteHistory() {
  const [recents, setRecents] = useState<string[]>(() => loadList(RECENTS_KEY));
  const [pins, setPins] = useState<string[]>(() => loadList(PINS_KEY));

  // Sync across palette instances in the same tab. Guard against echoing our own writes.
  useEffect(() => {
    const handler = () => {
      const freshRecents = loadList(RECENTS_KEY);
      const freshPins = loadList(PINS_KEY);
      setRecents((prev) => (sameList(prev, freshRecents) ? prev : freshRecents));
      setPins((prev) => (sameList(prev, freshPins) ? prev : freshPins));
    };
    window.addEventListener('workflows:history-changed', handler);
    return () => window.removeEventListener('workflows:history-changed', handler);
  }, []);

  // Persist + broadcast as an effect so Strict Mode's double-invoked reducer
  // does not toggle LocalStorage back to the initial state.
  useEffect(() => {
    saveList(RECENTS_KEY, recents);
    window.dispatchEvent(new CustomEvent('workflows:history-changed'));
  }, [recents]);

  useEffect(() => {
    saveList(PINS_KEY, pins);
    window.dispatchEvent(new CustomEvent('workflows:history-changed'));
  }, [pins]);

  const pushRecent = useCallback((id: string) => {
    setRecents((prev) => {
      if (prev[0] === id) return prev; // already on top
      const next = [id, ...prev.filter((x) => x !== id)].slice(0, MAX_RECENTS);
      return next;
    });
  }, []);

  const togglePin = useCallback((id: string) => {
    setPins((prev) =>
      prev.includes(id)
        ? prev.filter((x) => x !== id)
        : [id, ...prev].slice(0, MAX_PINS),
    );
  }, []);

  const isPinned = useCallback((id: string) => pins.includes(id), [pins]);

  return { recents, pins, pushRecent, togglePin, isPinned };
}

function sameList(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}
