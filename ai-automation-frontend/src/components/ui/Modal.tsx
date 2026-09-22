import { type ReactNode, useEffect, useRef, useState } from 'react';
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

export function Modal({ open, onClose, title, children, footer, size = 'md', resizable = false }: ModalProps) {
  useEffect(() => {
    if (open) {
      document.body.style.overflow = 'hidden';
      const handleEsc = (e: KeyboardEvent) => {
        if (e.key === 'Escape') onClose();
      };
      window.addEventListener('keydown', handleEsc);
      return () => {
        document.body.style.overflow = '';
        window.removeEventListener('keydown', handleEsc);
      };
    }
  }, [open, onClose]);

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
        className={`relative mx-2 sm:mx-4 bg-surface-overlay border border-border rounded-xl shadow-2xl animate-slide-up ${
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
          <h2 className="text-lg font-semibold text-text">{title}</h2>
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
              aria-label="Close"
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
