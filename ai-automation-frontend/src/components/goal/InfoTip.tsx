import { useCallback, useId, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Info } from 'lucide-react';
import { FLOATING_STYLE, useFloating, type FloatingAnchor } from './floating';

/**
 * Ein Info-Symbol mit Erklärung (c60bf914, Flo: „Wächter muss erklärt werden, vielleicht ein Info zum
 * Drüberfahren“). Die Erklärung erscheint beim Drüberfahren, beim Fokus per Tastatur und beim Tippen;
 * ein Klick heftet sie an, bis erneut geklickt, der Fokus verlassen oder Escape gedrückt wird. Sie
 * schwebt per Portal (`floating.ts`) und ist über `aria-describedby` auch vorgelesen.
 */
export function InfoTip({ label, children, testId }: {
  /** Name des Symbols, etwa „Was macht der Wächter?“. */
  label: string;
  children: ReactNode;
  testId?: string;
}) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [pinned, setPinned] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const anchor = useCallback<FloatingAnchor>(() => triggerRef.current?.getBoundingClientRect() ?? null, []);
  const close = () => {
    setOpen(false);
    setPinned(false);
  };

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
      {open && <Tip id={id} anchor={anchor}>{children}</Tip>}
    </>
  );
}

function Tip({ id, anchor, children }: { id: string; anchor: FloatingAnchor; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useFloating(anchor, ref, 'start');
  return createPortal(
    <div ref={ref} id={id} role="tooltip" style={FLOATING_STYLE}
      className="z-50 w-80 max-w-[calc(100vw-1rem)] space-y-1.5 rounded-lg border border-border bg-surface-overlay p-3 text-left text-xs leading-relaxed text-text-secondary shadow-lg">
      {children}
    </div>,
    document.body,
  );
}
