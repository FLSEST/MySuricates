import { useEffect, useRef, useState, useSyncExternalStore } from 'react';

function subscribeReducedMotion(onChange: () => void) {
  if (typeof window === 'undefined' || !window.matchMedia) return () => {};
  const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
  mq.addEventListener('change', onChange);
  return () => mq.removeEventListener('change', onChange);
}

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

interface TimelineClockOptions {
  /** Identifies the timeline; a new key restarts the clock at 0. */
  key: string;
  /** Length of one pass in seconds. */
  total: number;
  /** False pauses the frame loop without losing the position (e.g. while collapsed). */
  running: boolean;
}

/**
 * Clock of an explanatory animation in seconds, driven by requestAnimationFrame. It loops by
 * default; under prefers-reduced-motion it waits for `togglePlaying` and stops after one pass.
 * Playing again after the end starts from the beginning.
 */
export function useTimelineClock({ key, total, running }: TimelineClockOptions) {
  const reducedMotion = useSyncExternalStore(subscribeReducedMotion, prefersReducedMotion, () => false);
  const [playing, setPlaying] = useState(() => !prefersReducedMotion());
  const [clock, setClock] = useState({ key, t: 0 });
  const clockRef = useRef(clock);
  const elapsed = clock.key === key ? clock.t : 0;

  useEffect(() => {
    if (!playing || !running) return;
    let raf = 0;
    let last = performance.now();
    const tick = (now: number) => {
      const prev = clockRef.current;
      let next = (prev.key === key ? prev.t : 0) + (now - last) / 1000;
      last = now;
      const finished = next >= total && reducedMotion;
      if (next >= total) next = reducedMotion ? total : next % total;
      clockRef.current = { key, t: next };
      setClock(clockRef.current);
      if (finished) setPlaying(false);
      else raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [playing, running, key, total, reducedMotion]);

  const togglePlaying = () => {
    if (!playing && elapsed >= total) {
      clockRef.current = { key, t: 0 };
      setClock(clockRef.current);
    }
    setPlaying(!playing);
  };

  return { elapsed, playing, togglePlaying };
}
