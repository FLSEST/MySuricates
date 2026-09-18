import { useSyncExternalStore } from 'react';
import { rateLimitBackoffUntil, subscribeRateLimitBackoff } from '../api/client';

/**
 * Ende des aktiven 429-Backoffs als Epoch-ms, 0 wenn nichts gesperrt ist (c168293d).
 *
 * Der Client meldet Beginn *und* Ablauf einer Sperre, ein eigener Takt ist also nicht nötig.
 */
export function useRateLimitBackoffUntil(): number {
  return useSyncExternalStore(subscribeRateLimitBackoff, () => rateLimitBackoffUntil(), () => 0);
}
