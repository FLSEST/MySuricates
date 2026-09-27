import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';

/** So lange nach dem letzten Tastendruck wird gespeichert. */
export const NUDGE_DELAY = 700;

interface Latest<T> {
  draft: T | null;
  committed: T;
  same: (a: T, b: T) => boolean;
  commit: (value: T) => void;
}

function stopTimer(timer: RefObject<number | null>) {
  if (timer.current !== null) window.clearTimeout(timer.current);
  timer.current = null;
}

/** Speichert einen Entwurf, der sich vom gespeicherten Stand unterscheidet. */
function saveDraft<T>(latest: RefObject<Latest<T>>) {
  const { draft, committed, same, commit } = latest.current;
  if (draft !== null && !same(draft, committed)) commit(draft);
}

/**
 * Tastatur für Balken und Rauten am Zeitstrahl (c60bf914): Die Pfeiltasten verschieben sofort
 * sichtbar (ein Entwurf), gespeichert wird erst nach einer kurzen Pause, beim Verlassen oder mit
 * Enter – nicht bei jedem Tastendruck, sonst liefen viele Aufrufe in unklarer Reihenfolge. Escape
 * verwirft den Entwurf.
 */
export function useKeyboardNudge<T>(committed: T, same: (a: T, b: T) => boolean, commit: (value: T) => void, delay = NUDGE_DELAY) {
  const [draft, setDraft] = useState<T | null>(null);
  const latest = useRef<Latest<T>>({ draft, committed, same, commit });
  const timer = useRef<number | null>(null);
  useLayoutEffect(() => {
    latest.current = { draft, committed, same, commit };
  });

  /** Speichert einen offenen Entwurf jetzt. */
  const flush = useCallback(() => {
    stopTimer(timer);
    saveDraft(latest);
    latest.current.draft = null;
    setDraft(null);
  }, []);

  /** Nächster Stand aus einem Tastendruck; die Pause beginnt von vorn. */
  const nudge = useCallback((next: T) => {
    latest.current.draft = next;
    setDraft(next);
    stopTimer(timer);
    timer.current = window.setTimeout(flush, delay);
  }, [delay, flush]);

  const cancel = useCallback(() => {
    stopTimer(timer);
    latest.current.draft = null;
    setDraft(null);
  }, []);

  // Verschwindet das Element mit offenem Entwurf, wird er trotzdem gespeichert
  useEffect(() => () => {
    if (timer.current === null) return;
    stopTimer(timer);
    saveDraft(latest);
  }, []);

  return { value: draft ?? committed, active: draft !== null, nudge, flush, cancel };
}
