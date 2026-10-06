import { useLayoutEffect, type CSSProperties, type RefObject } from 'react';

/**
 * Floating cards on the timeline (c60bf914): They hang via portal on `document.body` and stand
 * `position: fixed` at the trigger. This way the timeline does not cut them off, as it scrolls internally
 * on narrow screens (`overflow-x: auto` forces vertical clipping there too).
 */

/** Where the card is aligned to the trigger: left edge, right edge or centre. */
export type FloatingAlign = 'start' | 'end' | 'center';

/** Rectangle at which the card stands - usually that of the trigger, on a click on the axis a point. */
export type FloatingAnchor = () => DOMRect | null;

const GAP = 4;
const MARGIN = 8;

/**
 * Position of the card relative to the anchor: below, if it does not fit there and there is more room above, above; always
 * within the window. Pure and testable.
 */
export function floatingPosition(
  anchor: Pick<DOMRect, 'left' | 'right' | 'top' | 'bottom' | 'width'>,
  size: { width: number; height: number },
  viewport: { width: number; height: number },
  align: FloatingAlign,
): { left: number; top: number } {
  const wanted = align === 'start' ? anchor.left : align === 'end' ? anchor.right - size.width : anchor.left + anchor.width / 2 - size.width / 2;
  const left = Math.max(MARGIN, Math.min(wanted, viewport.width - size.width - MARGIN));
  const below = anchor.bottom + GAP;
  const above = anchor.top - GAP - size.height;
  const fitsBelow = below + size.height <= viewport.height - MARGIN;
  const top = fitsBelow || above < MARGIN || viewport.height - anchor.bottom >= anchor.top ? below : above;
  return { left, top: Math.max(MARGIN, top) };
}

/** Initial style of a floating card: invisible until `useFloating` has measured and set it. */
export const FLOATING_STYLE: CSSProperties = { position: 'fixed', left: 0, top: 0, visibility: 'hidden' };

/**
 * Places a floating card at its anchor - on opening, on scrolling (also in the timeline) and
 * on window resize, so it travels with its trigger. Writes the position directly to the
 * element (no state, no extra render); React does not touch `left`/`top` again as long as
 * the initial style stays the same.
 */
export function useFloating(anchor: FloatingAnchor, ref: RefObject<HTMLElement | null>, align: FloatingAlign) {
  useLayoutEffect(() => {
    let frame = 0;
    const place = () => {
      const rect = anchor();
      const element = ref.current;
      if (!rect || !element) return;
      const { left, top } = floatingPosition(rect, { width: element.offsetWidth, height: element.offsetHeight },
        { width: window.innerWidth, height: window.innerHeight }, align);
      element.style.left = `${left}px`;
      element.style.top = `${top}px`;
      element.style.visibility = 'visible';
    };
    const schedule = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(place);
    };
    place();
    window.addEventListener('scroll', schedule, true);
    window.addEventListener('resize', schedule);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener('scroll', schedule, true);
      window.removeEventListener('resize', schedule);
    };
  }, [anchor, ref, align]);
}

/**
 * On closing, gives the focus back to the trigger - after saving as after cancelling -, unless
 * someone has meanwhile deliberately put it elsewhere (click on another control).
 */
export function returnFocus(trigger: HTMLElement | null | undefined, container: HTMLElement | null) {
  const active = document.activeElement;
  if (!trigger || !trigger.isConnected) return;
  if (!active || active === document.body || (container && container.contains(active))) trigger.focus({ preventScroll: true });
}
