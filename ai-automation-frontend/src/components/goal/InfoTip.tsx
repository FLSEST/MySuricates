import { useCallback, useEffect, useId, useRef, useState, type ReactNode, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { Info } from 'lucide-react';
import { FLOATING_STYLE, useFloating, type FloatingAnchor } from './floating';

/**
 * An info icon with an explanation (c60bf914, Flo: „Wächter muss erklärt werden, vielleicht ein Info zum
 * Drüberfahren“). The explanation appears on hover, on keyboard focus and on tap;
 * a click pins it until clicked again, clicked elsewhere, focus is left or Escape is
 * pressed (clicking elsewhere is handled separately: Safari does not focus buttons on click, so no blur occurs there). It
 * floats via portal (`floating.ts`) and is also read aloud via `aria-describedby`.
 */
export function InfoTip({ label, children, testId }: {
  /** Name of the icon, e.g. „Was macht der Wächter?“. */
  label: string;
  children: ReactNode;
  testId?: string;
}) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [pinned, setPinned] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const tipRef = useRef<HTMLDivElement>(null);
  const anchor = useCallback<FloatingAnchor>(() => triggerRef.current?.getBoundingClientRect() ?? null, []);
  const close = () => {
    setOpen(false);
    setPinned(false);
  };

  // A click elsewhere closes — as with TimelinePopover
  useEffect(() => {
    if (!open) return;
    const closeOnOutsideClick = (event: MouseEvent) => {
      const target = event.target as Node;
      if (triggerRef.current?.contains(target) || tipRef.current?.contains(target)) return;
      setOpen(false);
      setPinned(false);
    };
    document.addEventListener('mousedown', closeOnOutsideClick);
    return () => document.removeEventListener('mousedown', closeOnOutsideClick);
  }, [open]);

  return (
    <>
      <button ref={triggerRef} type="button" aria-label={label} aria-expanded={open} aria-describedby={open ? id : undefined}
        data-testid={testId}
        onMouseEnter={() => setOpen(true)}
        onMouseLeave={() => { if (!pinned) setOpen(false); }}
        onFocus={() => setOpen(true)}
        onBlur={close}
        onClick={() => {
          if (pinned) close();
          else {
            setPinned(true);
            setOpen(true);
          }
        }}
        onKeyDown={(event) => {
          if (event.key !== 'Escape' || !open) return;
          event.preventDefault();
          event.stopPropagation();
          close();
        }}
        className="inline-flex h-5 w-5 items-center justify-center rounded-full text-text-tertiary hover:text-accent focus-ring">
        <Info className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
      {open && <Tip id={id} anchor={anchor} tipRef={tipRef}>{children}</Tip>}
    </>
  );
}

function Tip({ id, anchor, tipRef, children }: { id: string; anchor: FloatingAnchor; tipRef: RefObject<HTMLDivElement | null>; children: ReactNode }) {
  useFloating(anchor, tipRef, 'start');
  return createPortal(
    <div ref={tipRef} id={id} role="tooltip" style={FLOATING_STYLE}
      className="z-50 w-80 max-w-[calc(100vw-1rem)] space-y-1.5 rounded-lg border border-border bg-surface-overlay p-3 text-left text-xs leading-relaxed text-text-secondary shadow-lg">
      {children}
    </div>,
    document.body,
  );
}
