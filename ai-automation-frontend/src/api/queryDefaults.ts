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
    retry: defaultQueryRetry,
    refetchOnWindowFocus: false,
    refetchOnMount: (query: Query) => !(query.state.status === 'error' && isRateLimitBackoffActive()),
    get retryOnMount() {
      return !isRateLimitBackoffActive();
    },
  },
};

/**
 * Holt die App nach Ablauf der Sperre von selbst wieder ein (f3fb193f). `refetchOnMount` und
 * `retryOnMount` greifen nur beim Mount — dauerhaft montierte Queries (Sidebar, Layout,
 * Dashboard) blieben sonst bis zum Reload im Fehlerzustand, weil `retry` für 429 aus ist.
 * Deshalb wird beim Übergang aktiv→inaktiv einmal alles Fehlgeschlagene invalidiert.
 *
 * Gibt die Abmeldung zurück (Tests; in der App läuft das Abo über die Lebenszeit der Seite).
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
