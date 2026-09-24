import { useRef, useState } from 'react';

/** One resizable edge of the panel. */
export interface ResizeAxis {
  size: number;
  onChange: (size: number) => void;
  min: number;
  /** Room the panel keeps to the window: the size grows at most to the window's extent minus this. */
  margin: number;
}

type Edge = 'width' | 'height';
type HandlePointerEvent = React.PointerEvent<HTMLElement>;

const coord = (edge: Edge, e: HandlePointerEvent) => (edge === 'width' ? e.clientX : e.clientY);
const windowExtent = (edge: Edge) => (edge === 'width' ? window.innerWidth : window.innerHeight);

/**
 * Pointer resizing for a panel anchored bottom right: the left edge sets the width, the top edge
 * the height, and dragging away from the anchor (left or up) makes it bigger. Pointer capture keeps
 * the drag on its handle even when the pointer leaves it. The upper bound is read from the window on
 * every move. Spread `widthHandle` / `heightHandle` onto the two handle elements.
 */
export function usePanelResize({ width, height }: { width: ResizeAxis; height: ResizeAxis }) {
  const [isResizing, setIsResizing] = useState(false);
  // One drag per edge, like two separate handles: a move on one handle never resizes the other edge
  const drags = useRef<Record<Edge, { start: number; startSize: number } | null>>({ width: null, height: null });

  const press = (edge: Edge, axis: ResizeAxis, e: HandlePointerEvent) => {
    e.currentTarget.setPointerCapture(e.pointerId);
    drags.current[edge] = { start: coord(edge, e), startSize: axis.size };
    setIsResizing(true);
    e.preventDefault();
  };

  const move = (edge: Edge, axis: ResizeAxis, e: HandlePointerEvent) => {
    const drag = drags.current[edge];
    if (!drag) return;
    const size = drag.startSize + (drag.start - coord(edge, e));
    axis.onChange(Math.min(windowExtent(edge) - axis.margin, Math.max(axis.min, size)));
  };

  const release = (edge: Edge) => {
    drags.current[edge] = null;
    setIsResizing(false);
  };

  return {
    isResizing,
    widthHandle: {
      onPointerDown: (e: HandlePointerEvent) => press('width', width, e),
      onPointerMove: (e: HandlePointerEvent) => move('width', width, e),
      onPointerUp: () => release('width'),
    },
    heightHandle: {
      onPointerDown: (e: HandlePointerEvent) => press('height', height, e),
      onPointerMove: (e: HandlePointerEvent) => move('height', height, e),
      onPointerUp: () => release('height'),
    },
  };
}
