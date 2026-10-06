import { useLayoutEffect, useState, type RefObject } from 'react';

/**
 * Width of an element in px, kept up to date via ResizeObserver (which delivers the first measurement
 * right on observing). 0 as long as nothing is measured – e.g. in jsdom; the caller then takes a
 * fallback value.
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
