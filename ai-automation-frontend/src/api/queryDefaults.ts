import type { DefaultOptions, Query, QueryClient } from '@tanstack/react-query';
import { apiErrorStatus, isRateLimitBackoffActive, subscribeRateLimitBackoff } from './client';
import { isModuleDisabledError } from '../hooks/useModules';

/** Retry rule of the app: once, never for a 429 or a disabled module. Hooks that add a rule build on it. */
export function defaultQueryRetry(failureCount: number, error: unknown): boolean {
  // Never retry rate limit errors — retries make the problem worse
  if (apiErrorStatus(error) === 429) return false;
  // A disabled module stays disabled until the backend restarts
  if (isModuleDisabledError(error)) return false;
  return failureCount < 1;
}

/**
 * QueryClient defaults of the app. A module of its own so tests run against exactly the defaults
 * that `main.tsx` uses.
 *
 * On the backoff (f3fb193f): a failed query has no data and `dataUpdatedAt = 0` —
 * so despite `staleTime` it counts as stale and fires again on every mount. TanStack
 * checks two different switches for this:
 *  - without data, `retryOnMount` decides (boolean, read when the defaults are merged),
 *  - with data (e.g. `initialData` from the cache), `refetchOnMount` decides.
 * Both are derived from the backoff state here. `retryOnMount` is deliberately a
 * getter: `defaultQueryOptions()` spreads the defaults anew on every mount/render, so the
 * value is evaluated exactly when it is needed.
 */
export const appQueryDefaults: DefaultOptions = {
  queries: {
    staleTime: 30_000,
    retry: defaultQueryRetry,
    refetchOnWindowFocus: false,
    refetchOnMount: (query: Query) => !(query.state.status === 'error' && isRateLimitBackoffActive()),
    get retryOnMount() {
      return !isRateLimitBackoffActive();
    },
  },
};

/**
 * Makes the app catch up by itself once the lock has expired (f3fb193f). `refetchOnMount` and
 * `retryOnMount` only take effect on mount — permanently mounted queries (sidebar, layout,
 * dashboard) would otherwise stay in the error state until reload, because `retry` is off for 429.
 * So on the active→inactive transition everything that failed is invalidated once.
 *
 * Returns the unsubscribe function (tests; in the app the subscription lives as long as the page).
 */
export function installRateLimitRecovery(queryClient: QueryClient): () => void {
  let wasActive = isRateLimitBackoffActive();
  return subscribeRateLimitBackoff(() => {
    const active = isRateLimitBackoffActive();
    if (wasActive && !active) {
      queryClient.invalidateQueries({ predicate: (query) => query.state.status === 'error' });
    }
    wasActive = active;
  });
}
