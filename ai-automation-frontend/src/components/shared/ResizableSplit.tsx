import { useRef, useState, type ReactNode } from 'react';
import { usePersistedState } from '../../hooks/usePersistedState';
import { useIsMobile } from '../../hooks/useIsMobile';

/** Side pane width in percent of the split container. */
export const SPLIT_DEFAULT_SIDE_PERCENT = 33;
export const SPLIT_MIN_SIDE_PERCENT = 20;
export const SPLIT_MAX_SIDE_PERCENT = 60;
const KEYBOARD_STEP = 2;

function clampSide(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return SPLIT_DEFAULT_SIDE_PERCENT;
  return Math.round(Math.min(SPLIT_MAX_SIDE_PERCENT, Math.max(SPLIT_MIN_SIDE_PERCENT, value)));
}

interface ResizableSplitProps {
  main: ReactNode;
  side: ReactNode;
  /** localStorage key for the side width — per viewer, survives reloads. */
  storageKey: string;
  separatorLabel: string;
  /** Hides the side pane (kept mounted) so the main pane takes the full width. */
  collapsed?: boolean;
  sideId?: string;
}

/**
 * Two panes side by side with a draggable divider (pointer, keyboard, double click = reset).
 * Below the `lg` breakpoint both panes stack and the divider and `collapsed` are ignored.
 */
export function ResizableSplit({ main, side, storageKey, separatorLabel, collapsed = false, sideId }: ResizableSplitProps) {
  const isMobile = useIsMobile();
  const [storedSide, setStoredSide] = usePersistedState<number>(storageKey, SPLIT_DEFAULT_SIDE_PERCENT);
  const sidePercent = clampSide(storedSide);
  const containerRef = useRef<HTMLDivElement>(null);
  // Container geometry is read once per drag, not on every move
  const dragRect = useRef<DOMRect | null>(null);
  const [dragging, setDragging] = useState(false);

  if (isMobile) {
    return (
      <div className="space-y-6">
        <div className="min-w-0">{main}</div>
        <div id={sideId} className="min-w-0">{side}</div>
      </div>
    );
  }

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!containerRef.current) return;
    // Optional call: jsdom has no pointer capture
    e.currentTarget.setPointerCapture?.(e.pointerId);
    dragRect.current = containerRef.current.getBoundingClientRect();
    setDragging(true);
    e.preventDefault();
  };

  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const rect = dragRect.current;
    if (!rect || rect.width <= 0) return;
    setStoredSide(clampSide(((rect.right - e.clientX) / rect.width) * 100));
  };

  const onPointerUp = () => {
    dragRect.current = null;
    setDragging(false);
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    // Keys follow the divider on screen: left widens the side pane on the right
    const next: Record<string, number> = {
      ArrowLeft: sidePercent + KEYBOARD_STEP,
      ArrowRight: sidePercent - KEYBOARD_STEP,
      Home: SPLIT_MAX_SIDE_PERCENT,
      End: SPLIT_MIN_SIDE_PERCENT,
    };
    if (!(e.key in next)) return;
    e.preventDefault();
    setStoredSide(clampSide(next[e.key]));
  };

  return (
    <div ref={containerRef} className="flex">
      <div className="flex-1 min-w-0">{main}</div>
      {!collapsed && (
        <div
          role="separator"
          aria-orientation="vertical"
          aria-label={separatorLabel}
          aria-controls={sideId}
          aria-valuenow={sidePercent}
          aria-valuemin={SPLIT_MIN_SIDE_PERCENT}
          aria-valuemax={SPLIT_MAX_SIDE_PERCENT}
          title={separatorLabel}
          tabIndex={0}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerUp}
          onDoubleClick={() => setStoredSide(SPLIT_DEFAULT_SIDE_PERCENT)}
          onKeyDown={onKeyDown}
          className="group relative w-6 shrink-0 cursor-col-resize touch-none select-none focus:outline-none"
        >
          <div
            className={`absolute inset-y-0 left-1/2 -translate-x-1/2 transition-colors ${
              dragging ? 'w-0.5 bg-accent' : 'w-px bg-border group-hover:w-0.5 group-hover:bg-accent group-focus-visible:w-0.5 group-focus-visible:bg-accent'
            }`}
          />
          {/* Grip stays in view while the page scrolls */}
          <div
            className={`sticky top-[40vh] mx-auto h-10 w-1.5 rounded-full transition-colors ${
              dragging ? 'bg-accent' : 'bg-border-strong group-hover:bg-accent group-focus-visible:bg-accent'
            }`}
          />
        </div>
      )}
      <div
        id={sideId}
        hidden={collapsed}
        className="shrink-0 min-w-0"
        style={{ flexBasis: `${sidePercent}%` }}
      >
        {side}
      </div>
    </div>
  );
}
