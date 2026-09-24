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

/** Without it a touch drag on the handle scrolls the page, and the browser cancels the pointer. */
const HANDLE_STYLE = { touchAction: 'none' } as const;

const coord = (edge: Edge, e: HandlePointerEvent) => (edge === 'width' ? e.clientX : e.clientY);
const windowExtent = (edge: Edge) => (edge === 'width' ? window.innerWidth : window.innerHeight);

/**
 * Pointer resizing for a panel anchored bottom right: the left edge sets the width, the top edge
 * the height, and dragging away from the anchor (left or up) makes it bigger. Pointer capture keeps
 * the drag on its handle even when the pointer leaves it. The upper bound is read from the window on
 * every move. A drag ends on release, on cancel and when the capture is lost — otherwise merely
 * passing over the handle would keep resizing. Spread `widthHandle` / `heightHandle` onto the two
 * handle elements; they carry `touch-action: none`, so touch input drags instead of scrolling.
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

  // Spelled out per edge: a helper building these objects during render would pass the ref
  // through a render-time call, which react-hooks/refs rejects.
  return {
    isResizing,
    widthHandle: {
      onPointerDown: (e: HandlePointerEvent) => press('width', width, e),
      onPointerMove: (e: HandlePointerEvent) => move('width', width, e),
      onPointerUp: () => release('width'),
      onPointerCancel: () => release('width'),
      onLostPointerCapture: () => release('width'),
      style: HANDLE_STYLE,
    },
    heightHandle: {
      onPointerDown: (e: HandlePointerEvent) => press('height', height, e),
      onPointerMove: (e: HandlePointerEvent) => move('height', height, e),
      onPointerUp: () => release('height'),
      onPointerCancel: () => release('height'),
      onLostPointerCapture: () => release('height'),
      style: HANDLE_STYLE,
    },
  };
}
