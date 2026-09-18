import { useSyncExternalStore } from 'react';
import { rateLimitBackoffUntil, subscribeRateLimitBackoff } from '../api/client';

/** Sekundentakt, in dem der Ablauf einer Sperre bemerkt wird. */
const TICK_MS = 1_000;

function subscribe(onStoreChange: () => void): () => void {
  const unsubscribe = subscribeRateLimitBackoff(onStoreChange);
  // Der Client meldet nur den Beginn einer Sperre; ihr Ablauf braucht einen eigenen Takt.
  // React verwirft Ticks ohne Wertänderung, der Takt kostet also nur den Timer.
  const timer = setInterval(onStoreChange, TICK_MS);
  return () => {
    unsubscribe();
    clearInterval(timer);
  };
}

/**
 * Ende des aktiven 429-Backoffs als Epoch-ms, 0 wenn nichts gesperrt ist (c168293d).
 */
export function useRateLimitBackoffUntil(): number {
  return useSyncExternalStore(subscribe, () => rateLimitBackoffUntil(), () => 0);
}
