import { useState, useCallback } from 'react';

/**
 * Drop-in replacement for useState that mirrors state to localStorage.
 * Reads the initial value from localStorage on mount; writes on every update.
 *
 * Usage:
 *   const [filter, setFilter] = usePersistedState('my-page-filter', 'ALL');
 *
 * Errors (e.g. private browsing, QuotaExceededError) are silently ignored so
 * the component always works even when localStorage is unavailable.
 */
export function usePersistedState<T>(
  key: string,
  defaultValue: T,
): [T, (value: T | ((prev: T) => T)) => void] {
  const [state, setState] = useState<T>(() => {
    try {
      const saved = localStorage.getItem(key);
      return saved !== null ? (JSON.parse(saved) as T) : defaultValue;
    } catch {
      return defaultValue;
    }
  });

  const setAndPersist = useCallback(
    (value: T | ((prev: T) => T)) => {
      setState((prev) => {
        const next = typeof value === 'function' ? (value as (p: T) => T)(prev) : value;
        try {
          localStorage.setItem(key, JSON.stringify(next));
        } catch {
          // ignore QuotaExceededError / SecurityError
        }
        return next;
      });
    },
    [key],
  );

  return [state, setAndPersist];
}
