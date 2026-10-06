import { useSyncExternalStore } from 'react';
import { rateLimitBackoffUntil, subscribeRateLimitBackoff } from '../api/client';

/**
 * End of the active 429 backoff as epoch ms, 0 if nothing is blocked (c168293d).
 *
 * The client reports the start *and* the expiry of a block, so a clock of its own is not needed.
 */
export function useRateLimitBackoffUntil(): number {
  return useSyncExternalStore(subscribeRateLimitBackoff, () => rateLimitBackoffUntil(), () => 0);
}
