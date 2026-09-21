import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';

export const ZOOM_MIN = 0.25;
export const ZOOM_MAX = 3;
/** Faktor je Zoom-Stufe (Knopf oder eine Mausrad-Raste). */
export const ZOOM_STEP = 1.25;
/** Eingepasst schrumpft das horizontale Diagramm höchstens auf diesen Anteil seiner Breite (dann Scrollen). */
export const FIT_MIN_WIDTH_RATIO = 0.75;

/** Eine Mausrad-Raste liefert ~100 px deltaY; Trackpad-Pinch kommt in kleinen Schritten mit ctrlKey. */
const WHEEL_DELTA_PER_STEP = 100;

const clamp = (scale: number) => Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, scale));

/**
 * Zoom des Ablauf-Diagramms. `scale === null` heißt eingepasst — das Verhalten ohne Zoom: horizontal
 * breitenfüllend (`fit: 'width'`), vertikal in nativer Größe (`fit: 'native'`). Der Zoom gilt nur für den
 * `layoutKey`, in dem er eingestellt wurde; ein Wechsel der Ausrichtung passt wieder ein.
 *
 * Strg/⌘ + Mausrad (und Trackpad-Pinch) zoomt am Mauszeiger, die Knöpfe um die Mitte des Ausschnitts.
 * Verschoben wird per Scrollen im Container `containerRef` — den Ref hält der Aufrufer, damit das
 * Rückgabeobjekt keinen Ref enthält und im Render gelesen werden darf.
 */
export function useDiagramZoom(
  containerRef: RefObject<HTMLDivElement | null>, layoutKey: string, contentWidth: number, fit: 'width' | 'native',
) {
  const [zoom, setZoom] = useState<{ key: string; scale: number } | null>(null);
  // Anderes Layout: Zoom verwerfen (abgeleiteter State im Render, kein Effekt) — zurück heißt eingepasst
  if (zoom && zoom.key !== layoutKey) setZoom(null);
  const scale = zoom && zoom.key === layoutKey ? zoom.scale : null;
  /** Scroll-Position, die den Anker nach dem nächsten Render an derselben Stelle hält. */
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

  // Nicht-passiv, sonst lässt sich der Seiten-Zoom des Browsers bei Strg + Mausrad nicht verhindern
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
