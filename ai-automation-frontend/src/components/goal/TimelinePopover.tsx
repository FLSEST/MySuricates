import { useCallback, useEffect, useLayoutEffect, useRef, type ReactNode, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { FLOATING_STYLE, returnFocus, useFloating, type FloatingAlign, type FloatingAnchor } from './floating';

const FOCUSABLE = 'input, select, textarea, button';
/** Ein Element mit diesem Attribut bekommt beim Öffnen den Fokus statt des ersten Felds. */
const AUTOFOCUS = '[data-autofocus]';

/**
 * Kleine, nicht modale Karte am Zeitstrahl – das Menü eines Chips, die Karte eines Arbeitspakets
 * oder Meilensteins. Sie schwebt per Portal über der Seite (`floating.ts`), damit der in sich scrollende
 * Zeitstrahl sie nicht abschneidet. Beim Öffnen steht der Fokus auf ihrem ersten Feld (oder dem mit
 * `data-autofocus`); Escape und ein Klick daneben schließen sie. Danach geht der Fokus an den
 * Auslöser zurück – nach Speichern wie nach Abbrechen.
 */
export function TimelinePopover({ label, onClose, anchor, align = 'start', triggerRef, wide = false, children }: {
  label: string;
  onClose: () => void;
  /** Wo die Karte steht; ohne eigenen Anker am Auslöser. */
  anchor?: FloatingAnchor;
  align?: FloatingAlign;
  /** Klicks auf den Auslöser schalten selbst um – sie zählen nicht als „daneben“. Er bekommt danach den Fokus zurück. */
  triggerRef?: RefObject<HTMLElement | null>;
  /** Breiter für Formulare mit zwei Datumsfeldern oder einer Auswahlliste. */
  wide?: boolean;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const atTrigger = useCallback<FloatingAnchor>(() => triggerRef?.current?.getBoundingClientRect() ?? null, [triggerRef]);
  useFloating(anchor ?? atTrigger, ref, align);

  useEffect(() => {
    (ref.current?.querySelector<HTMLElement>(AUTOFOCUS) ?? ref.current?.querySelector<HTMLElement>(FOCUSABLE))?.focus();
  }, []);

  // Beim Schließen – gleich wodurch – den Fokus an den Auslöser zurückgeben
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
