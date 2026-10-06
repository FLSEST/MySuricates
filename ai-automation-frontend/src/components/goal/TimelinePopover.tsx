import { useCallback, useEffect, useLayoutEffect, useRef, type ReactNode, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { FLOATING_STYLE, returnFocus, useFloating, type FloatingAlign, type FloatingAnchor } from './floating';

const FOCUSABLE = 'input, select, textarea, button';
/** An element with this attribute gets the focus on opening instead of the first field. */
const AUTOFOCUS = '[data-autofocus]';

/**
 * Small, non-modal card on the timeline - the menu of a chip, the card of a work package
 * or milestone. It floats via portal above the page (`floating.ts`) so that the internally scrolling
 * timeline does not cut it off. On opening, the focus is on its first field (or the one with
 * `data-autofocus`); Escape and a click outside close it. Afterwards the focus goes back to the
 * trigger - after saving as after cancelling.
 */
export function TimelinePopover({ label, onClose, anchor, align = 'start', triggerRef, wide = false, children }: {
  label: string;
  onClose: () => void;
  /** Where the card stands; without its own anchor, at the trigger. */
  anchor?: FloatingAnchor;
  align?: FloatingAlign;
  /** Clicks on the trigger toggle by themselves - they do not count as „outside“. It gets the focus back afterwards. */
  triggerRef?: RefObject<HTMLElement | null>;
  /** Wider for forms with two date fields or a selection list. */
  wide?: boolean;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const atTrigger = useCallback<FloatingAnchor>(() => triggerRef?.current?.getBoundingClientRect() ?? null, [triggerRef]);
  useFloating(anchor ?? atTrigger, ref, align);

  useEffect(() => {
    (ref.current?.querySelector<HTMLElement>(AUTOFOCUS) ?? ref.current?.querySelector<HTMLElement>(FOCUSABLE))?.focus();
  }, []);

  // On closing - however it happens - give the focus back to the trigger
  useLayoutEffect(() => {
    const node = ref.current;
    const trigger = triggerRef?.current;
    return () => returnFocus(trigger, node);
  }, [triggerRef]);

  useEffect(() => {
    const closeOnOutsideClick = (event: MouseEvent) => {
      const target = event.target as Node;
      if (ref.current?.contains(target) || triggerRef?.current?.contains(target)) return;
      onClose();
    };
    document.addEventListener('mousedown', closeOnOutsideClick);
    return () => document.removeEventListener('mousedown', closeOnOutsideClick);
  }, [onClose, triggerRef]);

  return createPortal(
    <div
      ref={ref}
      role="dialog"
      aria-label={label}
      onKeyDown={(event) => {
        if (event.key !== 'Escape') return;
        event.preventDefault();
        event.stopPropagation();
        onClose();
      }}
      style={FLOATING_STYLE}
      className={`z-50 ${wide ? 'w-80' : 'w-64'} max-w-[calc(100vw-1rem)] rounded-lg border border-border bg-surface-overlay p-3 text-left shadow-lg`}
    >
      {children}
    </div>,
    document.body,
  );
}
