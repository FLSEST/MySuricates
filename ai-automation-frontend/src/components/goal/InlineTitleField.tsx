import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { FLOATING_STYLE, returnFocus, useFloating, type FloatingAnchor } from './floating';
import { MAX_TITLE } from './workPackageForm';

/** Ein Feld im Fluss schwebt nicht. */
const NO_ANCHOR: FloatingAnchor = () => null;

/**
 * Das Feld für ein neues Arbeitspaket oder einen neuen Meilenstein am Ort (c60bf914): Projekt und
 * Tag stehen schon fest, nur der Titel fehlt. Enter legt an, Escape bricht ab, ein leeres Feld
 * verschwindet beim Verlassen. Danach bekommt der Auslöser (Tageswähler oder „+“) den Fokus zurück.
 * An einem Tag schwebt es per Portal unter der Zeile (`floating.ts`), damit der in sich scrollende
 * Zeitstrahl es nicht abschneidet; in der Leiste „ohne Zeitraum“ steht es im Fluss.
 */
export function InlineTitleField({ label, placeholder, pending, triggerRef, floating, onSubmit, onCancel }: {
  label: string;
  placeholder: string;
  pending: boolean;
  triggerRef: RefObject<HTMLElement | null>;
  /** Anker, wenn das Feld schwebt. */
  floating?: FloatingAnchor;
  onSubmit: (title: string) => void;
  onCancel: () => void;
}) {
  const [title, setTitle] = useState('');
  const ref = useRef<HTMLFormElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  useFloating(floating ?? NO_ANCHOR, ref, 'start');
  // Der Fokus springt bewusst hinein: das Feld erscheint auf einen Klick oder Enter hin. Nicht per
  // `autoFocus` – das griffe, solange das schwebende Feld noch unsichtbar ist (`FLOATING_STYLE`), und
  // ein unsichtbares Feld nimmt keinen Fokus an. `useFloating` macht es im Layout-Effekt sichtbar.
  useEffect(() => {
    inputRef.current?.focus();
  }, []);
  // Beim Schließen geht der Fokus an den Auslöser zurück. Das Verlassen dabei ist kein Abbruch – auch
  // nicht, wenn StrictMode (Entwicklung) die Effekte nur zum Schein abräumt und gleich neu setzt: Sonst
  // schlösse sich das eben geöffnete, noch leere Feld sofort wieder.
  const closing = useRef(false);
  useLayoutEffect(() => {
    closing.current = false;
    const node = ref.current;
    const trigger = triggerRef.current;
    return () => {
      closing.current = true;
      returnFocus(trigger, node);
    };
  }, [triggerRef]);

  const form = (
    <form ref={ref} data-testid="inline-title" style={floating ? FLOATING_STYLE : undefined}
      className={floating ? 'z-50 w-56 max-w-[calc(100vw-1rem)]' : 'w-56 max-w-full'}
      onSubmit={(event) => {
        event.preventDefault();
        if (title.trim() && !pending) onSubmit(title.trim());
      }}>
      <input ref={inputRef} aria-label={label} placeholder={placeholder} value={title} maxLength={MAX_TITLE}
        disabled={pending}
        onChange={(event) => setTitle(event.target.value)}
        onKeyDown={(event) => {
          if (event.key !== 'Escape') return;
          event.preventDefault();
          event.stopPropagation();
          onCancel();
        }}
        onBlur={() => {
          if (!closing.current && !title.trim()) onCancel();
        }}
        className="input-base w-full py-1 text-sm shadow-lg" />
    </form>
  );
  return floating ? createPortal(form, document.body) : form;
}
