import { useId, useState, type ReactNode } from 'react';
import { ChevronRight } from 'lucide-react';

/**
 * A collapsed section behind a labelled toggle. Collapsed content is not rendered at all, so
 * long step outputs cost nothing until someone asks for them.
 */
export function Disclosure({
  label, children, defaultOpen = false, className = '',
}: { label: ReactNode; children: ReactNode; defaultOpen?: boolean; className?: string }) {
  const [open, setOpen] = useState(defaultOpen);
  const contentId = useId();
  return (
    <div className={className}>
      <button
        type="button"
        aria-expanded={open}
        aria-controls={contentId}
        onClick={() => setOpen((value) => !value)}
        className="flex items-center gap-1.5 text-sm font-medium text-text-secondary hover:text-text transition-colors"
      >
        <ChevronRight className={`w-4 h-4 transition-transform ${open ? 'rotate-90' : ''}`} aria-hidden="true" />
        {label}
      </button>
      {open && <div id={contentId} className="mt-3">{children}</div>}
    </div>
  );
}
