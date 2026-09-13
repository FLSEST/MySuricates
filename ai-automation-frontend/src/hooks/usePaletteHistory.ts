import { useCallback, useEffect } from 'react';
import { useSyncedState } from './useSyncedState';

const MAX_RECENTS = 10;
const MAX_PINS = 8;
const EVENT_NAME = 'workflows:history-changed';

function recentsKey(contextId: string): string {
  return contextId === 'workflow' ? 'workflows-recents' : `palette-recents-${contextId}`;
}

function pinsKey(contextId: string): string {
  return contextId === 'workflow' ? 'workflows-pins' : `palette-pins-${contextId}`;
}

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
 * LocalStorage-backed recent + pinned IDs for the Command Palette, scoped per context.
 * Keys: {contextId}-recents / {contextId}-pins. The 'workflow' context keeps the legacy
 * keys `workflows-recents` / `workflows-pins` so users don't lose existing pins.
 * Persistence happens in useEffect to survive Strict Mode's double-invoked reducer.
 */
export function usePaletteHistory(contextId: string) {
  const rKey = recentsKey(contextId);
  const pKey = pinsKey(contextId);

  // Lists are loaded for the current keys; when the context (and thus the key) changes, they are
  // swapped during render, before the persistence effects below can write the old list under
  // the new key. (Callers usually re-mount via React key anyway, so this is defensive.)
  const [recents, setRecents] = useSyncedState<string[], string>(rKey, loadList);
  const [pins, setPins] = useSyncedState<string[], string>(pKey, loadList);

  useEffect(() => {
    const handler = () => {
      const freshRecents = loadList(rKey);
      const freshPins = loadList(pKey);
      setRecents((prev) => (sameList(prev, freshRecents) ? prev : freshRecents));
      setPins((prev) => (sameList(prev, freshPins) ? prev : freshPins));
    };
    window.addEventListener(EVENT_NAME, handler);
    return () => window.removeEventListener(EVENT_NAME, handler);
  }, [rKey, pKey, setRecents, setPins]);

  useEffect(() => {
    saveList(rKey, recents);
    window.dispatchEvent(new CustomEvent(EVENT_NAME));
  }, [recents, rKey]);

  useEffect(() => {
    saveList(pKey, pins);
    window.dispatchEvent(new CustomEvent(EVENT_NAME));
  }, [pins, pKey]);

  const pushRecent = useCallback((id: string) => {
    setRecents((prev) => {
      if (prev[0] === id) return prev;
      return [id, ...prev.filter((x) => x !== id)].slice(0, MAX_RECENTS);
    });
  }, [setRecents]);

  const togglePin = useCallback((id: string) => {
    setPins((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [id, ...prev].slice(0, MAX_PINS),
    );
  }, [setPins]);

  const isPinned = useCallback((id: string) => pins.includes(id), [pins]);

  return { recents, pins, pushRecent, togglePin, isPinned };
}

function sameList(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}
