import type { DefaultOptions, Query } from '@tanstack/react-query';
import { isRateLimitBackoffActive } from './client';
import { isModuleDisabledError } from '../hooks/useModules';

/**
 * QueryClient-Defaults der App. Als eigenes Modul, damit Tests gegen genau die Defaults
 * laufen, die `main.tsx` verwendet.
 *
 * Zum Backoff (f3fb193f): Eine gescheiterte Query hat keine Daten und `dataUpdatedAt = 0` —
 * sie gilt damit trotz `staleTime` als stale und feuert bei jedem Mount erneut. TanStack
 * fragt dafür zwei verschiedene Schalter ab:
 *  - ohne Daten entscheidet `retryOnMount` (Boolean, beim Mergen der Defaults gelesen),
 *  - mit Daten (z. B. `initialData` aus dem Cache) entscheidet `refetchOnMount`.
 * Beide werden hier aus dem Backoff-Zustand abgeleitet. `retryOnMount` ist bewusst ein
 * Getter: `defaultQueryOptions()` spreadet die Defaults bei jedem Mount/Render neu, der
 * Wert wird also genau dann ausgewertet, wenn er gebraucht wird.
 */
export const appQueryDefaults: DefaultOptions = {
  queries: {
    staleTime: 30_000,
    retry: (failureCount: number, error: unknown) => {
      // Never retry rate limit errors — retries make the problem worse
      if (error && typeof error === 'object' && 'status' in error && (error as { status: number }).status === 429) return false;
      // A disabled module stays disabled until the backend restarts
      if (isModuleDisabledError(error)) return false;
      return failureCount < 1;
    },
    refetchOnWindowFocus: false,
    refetchOnMount: (query: Query) => !(query.state.status === 'error' && isRateLimitBackoffActive()),
    get retryOnMount() {
      return !isRateLimitBackoffActive();
    },
  },
};
