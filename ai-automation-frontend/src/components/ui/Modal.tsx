import { type ReactNode, useEffect, useId, useRef, useState } from 'react';
import { Maximize2, Minimize2, X } from 'lucide-react';
import { useT } from '../../i18n';

interface ModalProps {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  footer?: ReactNode;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /**
   * The user can drag the lower right corner to resize the dialog and maximise it from the header.
   * The body then becomes a flex column — children can grow with `flex-1` (e.g. an editor).
   */
  resizable?: boolean;
}

const sizeStyles = {
  sm: 'max-w-md',
  md: 'max-w-lg',
  lg: 'max-w-2xl',
  xl: 'max-w-5xl',
};

// Resizable dialogs start at the same width but may be dragged wider than their size class
const resizableWidths = {
  sm: 'w-[min(28rem,96vw)]',
  md: 'w-[min(32rem,96vw)]',
  lg: 'w-[min(42rem,96vw)]',
  xl: 'w-[min(64rem,96vw)]',
};

/**
 * The open dialogs; the one with the highest `order` is on top. A confirmation can open above
 * another dialog (23635ef6): Escape then closes only the top one, the focus trap applies only to the
 * top one, and the page stays locked until the last closes. The order is taken when a dialog
 * renders open (parents render before their children, later siblings after earlier ones), not when
 * its effect runs – effects run children first, so an outer and an inner dialog opening in the same
 * commit would otherwise put the outer one on top (0d9072fa).
 */
const openDialogs = new Set<{ order: number }>();
let dialogCounter = 0;

function isTopDialog(dialog: { order: number }): boolean {
  for (const other of openDialogs) if (other.order > dialog.order) return false;
  return openDialogs.has(dialog);
}

const TABBABLE = [
  'a[href]', 'area[href]', 'button:not([disabled])', 'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])', 'textarea:not([disabled])', 'iframe', '[contenteditable="true"]',
  '[tabindex]:not([tabindex="-1"])',
].join(', ');

function tabbables(container: HTMLElement | null): HTMLElement[] {
  if (!container) return [];
  return [...container.querySelectorAll<HTMLElement>(TABBABLE)]
    .filter((element) => element.tabIndex >= 0 && !element.closest('[hidden], [inert]'));
}

export function Modal({ open, onClose, title, children, footer, size = 'md', resizable = false }: ModalProps) {
  if (!open) return null;

  // Own component so its size state resets with every open (it unmounts while closed)
  return (
    <ModalFrame onClose={onClose} title={title} footer={footer} size={size} resizable={resizable}>
      {children}
    </ModalFrame>
  );
}

function ModalFrame({ onClose, title, children, footer, size, resizable }: Omit<Required<ModalProps>, 'open' | 'footer'> & { footer?: ReactNode }) {
  const t = useT();
  const dialogRef = useRef<HTMLDivElement>(null);
  const [maximized, setMaximized] = useState(false);
  // Identity of this open dialog in `openDialogs`; the frame only lives while the dialog is open
  const [self] = useState(() => ({ order: ++dialogCounter }));
  // Taken while rendering, before a child's autoFocus moves the focus into the dialog
  const [opener] = useState(() => document.activeElement);
  const titleId = useId();

  useEffect(() => {
    openDialogs.add(self);
    document.body.style.overflow = 'hidden';
    // Initial focus: keep it where the content put it (autoFocus), else the first control of body
    // and footer – in a confirmation that is "Cancel" –, else the dialog itself
    const dialog = dialogRef.current;
    if (dialog && !dialog.contains(document.activeElement)) {
      const header = dialog.firstElementChild;
      const first = tabbables(dialog).find((element) => !header?.contains(element));
      (first ?? dialog).focus({ preventScroll: true });
    }
    return () => {
      openDialogs.delete(self);
      if (openDialogs.size === 0) document.body.style.overflow = '';
      // Focus back to what opened the dialog, unless the closing action moved it somewhere on purpose
      const active = document.activeElement;
      if (opener instanceof HTMLElement && opener.isConnected
          && (!active || active === document.body || !active.isConnected || dialog?.contains(active))) {
        opener.focus({ preventScroll: true });
      }
    };
  }, [self, opener]);

  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if (!isTopDialog(self)) return;
      if (e.key === 'Escape') {
        onClose();
      } else if (e.key === 'Tab' && !e.defaultPrevented) {
        // Focus trap: Tab and Shift+Tab cycle inside the dialog (an editor that inserts a tab
        // prevents the default and keeps its key)
        const dialog = dialogRef.current;
        if (!dialog) return;
        const items = tabbables(dialog);
        const first = items[0];
        const last = items.at(-1);
        const active = document.activeElement;
        const inside = !!active && dialog.contains(active);
        if (!first || !last) {
          e.preventDefault();
          dialog.focus({ preventScroll: true });
        } else if (e.shiftKey && (!inside || active === first || active === dialog)) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && (!inside || active === last)) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [self, onClose]);

  const toggleMaximized = () => {
    // Drop the size the user dragged to, so maximise and restore always land on a defined size
    dialogRef.current?.style.removeProperty('width');
    dialogRef.current?.style.removeProperty('height');
    setMaximized((m) => !m);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/60 backdrop-blur-xs animate-fade-in"
        onClick={onClose}
      />

      {/* Content */}
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={`relative mx-2 sm:mx-4 outline-none bg-surface-overlay border border-border rounded-xl shadow-2xl animate-slide-up ${
          !resizable
            ? `w-full ${sizeStyles[size]}`
            : maximized
              ? 'flex flex-col w-[96vw] h-[94vh] max-w-[96vw] max-h-[94vh]'
              : `flex flex-col ${resizableWidths[size]} max-w-[96vw] max-h-[94vh] min-w-[20rem] min-h-[16rem] resize overflow-hidden`
        }`}
        data-testid={resizable ? 'modal-resizable' : undefined}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-border shrink-0">
          <h2 id={titleId} className="text-lg font-semibold text-text">{title}</h2>
          <div className="flex items-center gap-1">
            {resizable && (
              <button
                type="button"
                onClick={toggleMaximized}
                className="p-2 rounded-lg text-text-tertiary hover:text-text hover:bg-surface-raised transition-colors"
                aria-label={maximized ? t.common.restore : t.common.maximize}
                title={maximized ? t.common.restore : t.common.maximize}
              >
                {maximized ? <Minimize2 className="w-4 h-4" /> : <Maximize2 className="w-4 h-4" />}
              </button>
            )}
            <button
              onClick={onClose}
              className="p-2 rounded-lg text-text-tertiary hover:text-text hover:bg-surface-raised transition-colors"
              aria-label={t.common.close}
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        </div>

        {/* Body */}
        {resizable ? (
          <div className="flex flex-col flex-1 min-h-0 px-5 py-4 overflow-y-auto">{children}</div>
        ) : (
          <div className="px-5 py-4 max-h-[80vh] sm:max-h-[75vh] overflow-y-auto">{children}</div>
        )}

        {/* Footer */}
        {footer && (
          <div className="flex items-center justify-end gap-3 px-5 py-4 border-t border-border shrink-0">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}
