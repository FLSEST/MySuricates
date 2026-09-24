import { useLayoutEffect, useState, type RefObject } from 'react';

/**
 * Breite eines Elements in px, nachgeführt per ResizeObserver (der die erste Messung gleich beim
 * Beobachten liefert). 0, solange nichts gemessen ist – etwa in jsdom; der Aufrufer nimmt dann einen
 * Ersatzwert.
 */
export function useElementWidth(ref: RefObject<HTMLElement | null>): number {
  const [width, setWidth] = useState(0);

  useLayoutEffect(() => {
    const element = ref.current;
    if (!element || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver((entries) => {
      const next = entries[0]?.contentRect.width ?? 0;
      setWidth((current) => (Math.abs(current - next) < 0.5 ? current : next));
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [ref]);

  return width;
}
