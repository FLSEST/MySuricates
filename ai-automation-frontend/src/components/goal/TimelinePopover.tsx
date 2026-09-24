import { useEffect, useRef, type CSSProperties, type ReactNode, type RefObject } from 'react';

const FOCUSABLE = 'input, select, textarea, button';
/** Ein Element mit diesem Attribut bekommt beim Öffnen den Fokus statt des ersten Felds. */
const AUTOFOCUS = '[data-autofocus]';

/**
 * Kleine, nicht modale Karte am Zeitstrahl – das Menü eines Chips, die Karte eines Meilensteins.
 * Beim Öffnen steht der Fokus auf ihrem ersten Feld (oder dem mit `data-autofocus`); Escape schließt
 * sie und gibt den Fokus an den Auslöser zurück, ein Klick daneben schließt sie ebenfalls.
 */
export function TimelinePopover({ label, onClose, triggerRef, className = '', style, children }: {
  label: string;
  onClose: () => void;
  /** Klicks auf den Auslöser schalten selbst um – sie zählen nicht als „daneben“. */
  triggerRef?: RefObject<HTMLElement | null>;
  className?: string;
  /** Lage am Zeitstrahl, etwa unter einer Raute. */
  style?: CSSProperties;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    (ref.current?.querySelector<HTMLElement>(AUTOFOCUS) ?? ref.current?.querySelector<HTMLElement>(FOCUSABLE))?.focus();
  }, []);

  useEffect(() => {
    const closeOnOutsideClick = (event: MouseEvent) => {
      const target = event.target as Node;
      if (ref.current?.contains(target) || triggerRef?.current?.contains(target)) return;
      onClose();
    };
    document.addEventListener('mousedown', closeOnOutsideClick);
    return () => document.removeEventListener('mousedown', closeOnOutsideClick);
  }, [onClose, triggerRef]);

  return (
    <div
      ref={ref}
      role="dialog"
      aria-label={label}
      onKeyDown={(event) => {
        if (event.key !== 'Escape') return;
        event.preventDefault();
        event.stopPropagation();
        onClose();
        triggerRef?.current?.focus();
      }}
      style={style}
      className={`absolute z-30 w-64 max-w-[calc(100vw-2rem)] rounded-lg border border-border bg-surface-overlay p-3 text-left shadow-lg ${className}`}
    >
      {children}
    </div>
  );
}
