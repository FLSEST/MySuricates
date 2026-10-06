import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';

export const ZOOM_MIN = 0.25;
export const ZOOM_MAX = 3;
/** Factor per zoom step (button or one mouse wheel notch). */
export const ZOOM_STEP = 1.25;
/** Fitted, the horizontal diagram shrinks to at most this fraction of its width (then scrolling). */
export const FIT_MIN_WIDTH_RATIO = 0.75;

/** One mouse wheel notch delivers ~100 px deltaY; trackpad pinch comes in small steps with ctrlKey. */
const WHEEL_DELTA_PER_STEP = 100;

const clamp = (scale: number) => Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, scale));

/**
 * Zoom of the flow diagram. `scale === null` means fitted — the behavior without zoom: horizontal
 * fills the width (`fit: 'width'`), vertical at native size (`fit: 'native'`). The zoom applies only to the
 * `layoutKey` in which it was set; a change of orientation fits again.
 *
 * Ctrl/⌘ + mouse wheel (and trackpad pinch) zooms at the mouse pointer, the buttons about the center of the viewport.
 * Panning is done by scrolling in the container `containerRef` — the caller holds the ref, so that the
 * return object contains no ref and may be read during render.
 */
export function useDiagramZoom(
  containerRef: RefObject<HTMLDivElement | null>, layoutKey: string, contentWidth: number, fit: 'width' | 'native',
) {
  const [zoom, setZoom] = useState<{ key: string; scale: number } | null>(null);
  // Different layout: discard zoom (derived state in render, no effect) — back means fitted
  if (zoom && zoom.key !== layoutKey) setZoom(null);
  const scale = zoom && zoom.key === layoutKey ? zoom.scale : null;
  /** Scroll position that keeps the anchor in the same place after the next render. */
  const pendingScroll = useRef<{ left: number; top: number } | null>(null);

  const zoomBy = useCallback((factor: number, anchor?: { x: number; y: number }) => {
    const el = containerRef.current;
    let current = scale;
    if (current === null) {
      const width = el?.clientWidth ?? 0;
      current = fit === 'native' || width <= 0 || contentWidth <= 0
        ? 1
        : Math.max(width, contentWidth * FIT_MIN_WIDTH_RATIO) / contentWidth;
    }
    const next = clamp(current * factor);
    if (next === current) return;
    if (el) {
      const a = anchor ?? { x: el.clientWidth / 2, y: el.clientHeight / 2 };
      const ratio = next / current;
      pendingScroll.current = { left: (el.scrollLeft + a.x) * ratio - a.x, top: (el.scrollTop + a.y) * ratio - a.y };
    }
    setZoom({ key: layoutKey, scale: next });
  }, [containerRef, scale, layoutKey, fit, contentWidth]);

  useLayoutEffect(() => {
    const el = containerRef.current;
    const target = pendingScroll.current;
    if (!el || !target) return;
    el.scrollLeft = Math.max(0, target.left);
    el.scrollTop = Math.max(0, target.top);
    pendingScroll.current = null;
  }, [containerRef, scale]);

  // Non-passive, otherwise the browser's page zoom cannot be prevented on Ctrl + mouse wheel
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      if (!e.ctrlKey && !e.metaKey) return;
      e.preventDefault();
      if (e.deltaY === 0) return;
      const steps = Math.min(1, Math.abs(e.deltaY) / WHEEL_DELTA_PER_STEP);
      const rect = el.getBoundingClientRect();
      zoomBy(ZOOM_STEP ** (e.deltaY < 0 ? steps : -steps), { x: e.clientX - rect.left, y: e.clientY - rect.top });
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [containerRef, zoomBy]);

  return {
    scale,
    zoomIn: () => zoomBy(ZOOM_STEP),
    zoomOut: () => zoomBy(1 / ZOOM_STEP),
    fit: () => setZoom(null),
    canZoomIn: scale === null || scale < ZOOM_MAX,
    canZoomOut: scale === null || scale > ZOOM_MIN,
  };
}
