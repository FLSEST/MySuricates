import { useEffect, useState } from 'react';

/** Like {@link useDebounced}, plus `flush`: take the current value right away instead of after the wait. */
export function useFlushableDebounce<T>(value: T, delay = 250): { debounced: T; flush: () => void } {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const h = setTimeout(() => setDebounced(() => value), delay);
    return () => clearTimeout(h);
  }, [value, delay]);
  return { debounced, flush: () => setDebounced(() => value) };
}

export function useDebounced<T>(value: T, delay = 250): T {
  return useFlushableDebounce(value, delay).debounced;
}
