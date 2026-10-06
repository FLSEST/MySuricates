import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';

/** Saving happens this long after the last keystroke. */
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

/** Saves a draft that differs from the saved state. */
function saveDraft<T>(latest: RefObject<Latest<T>>) {
  const { draft, committed, same, commit } = latest.current;
  if (draft !== null && !same(draft, committed)) commit(draft);
}

/**
 * Keyboard for bars and diamonds on the timeline (c60bf914): the arrow keys move immediately
 * visibly (a draft), saving happens only after a short pause, on leaving or with
 * Enter - not on every keystroke, otherwise many calls would run in unclear order. Escape
 * discards the draft.
 */
export function useKeyboardNudge<T>(committed: T, same: (a: T, b: T) => boolean, commit: (value: T) => void, delay = NUDGE_DELAY) {
  const [draft, setDraft] = useState<T | null>(null);
  const latest = useRef<Latest<T>>({ draft, committed, same, commit });
  const timer = useRef<number | null>(null);
  useLayoutEffect(() => {
    latest.current = { draft, committed, same, commit };
  });

  /** Saves an open draft now. */
  const flush = useCallback(() => {
    stopTimer(timer);
    saveDraft(latest);
    latest.current.draft = null;
    setDraft(null);
  }, []);

  /** Next state from a keystroke; the pause starts over. */
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

  // If the element disappears with an open draft, it is saved anyway
  useEffect(() => () => {
    if (timer.current === null) return;
    stopTimer(timer);
    saveDraft(latest);
  }, []);

  return { value: draft ?? committed, active: draft !== null, nudge, flush, cancel };
}
