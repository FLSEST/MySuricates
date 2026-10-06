import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { FLOATING_STYLE, returnFocus, useFloating, type FloatingAnchor } from './floating';
import { MAX_TITLE } from './workPackageForm';

/** A field in the flow does not float. */
const NO_ANCHOR: FloatingAnchor = () => null;

/**
 * The field for a new work package or a new milestone in place (c60bf914): project and
 * day are already fixed, only the title is missing. Enter creates, Escape cancels, an empty field
 * disappears on leaving. Afterwards the trigger (day picker or „+“) gets the focus back.
 * On a day it floats via portal below the row (`floating.ts`) so the self-scrolling
 * timeline does not clip it; in the „ohne Zeitraum“ bar it sits in the flow.
 */
export function InlineTitleField({ label, placeholder, pending, triggerRef, floating, onSubmit, onCancel }: {
  label: string;
  placeholder: string;
  pending: boolean;
  triggerRef: RefObject<HTMLElement | null>;
  /** Anchor when the field floats. */
  floating?: FloatingAnchor;
  onSubmit: (title: string) => void;
  onCancel: () => void;
}) {
  const [title, setTitle] = useState('');
  const ref = useRef<HTMLFormElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  useFloating(floating ?? NO_ANCHOR, ref, 'start');
  // Focus deliberately jumps in: the field appears on a click or Enter. Not via
  // `autoFocus` — that would fire while the floating field is still invisible (`FLOATING_STYLE`), and
  // an invisible field does not accept focus. `useFloating` makes it visible in the layout effect.
  useEffect(() => {
    inputRef.current?.focus();
  }, []);
  // On closing, focus goes back to the trigger. Leaving then is not a cancel — even
  // not when StrictMode (development) only pretends to tear down the effects and sets them up again right away: otherwise
  // the just-opened, still empty field would close again immediately.
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
