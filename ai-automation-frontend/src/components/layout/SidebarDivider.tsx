import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical, X } from 'lucide-react';
import { useT } from '../../i18n';

const LINE_STYLE = { borderTop: '1px solid var(--color-sidebar-border)' };

/** Subtle separator between sidebar groups; a name shows as a small heading when labels are visible. */
export function SidebarDivider({ label, showLabels }: { label?: string; showLabels: boolean }) {
  if (label && showLabels) {
    return (
      <div
        role="separator"
        aria-label={label}
        className="truncate px-3 pt-3 pb-1 text-[10px] font-semibold uppercase tracking-wider text-[var(--color-sidebar-text-muted)]"
      >
        {label}
      </div>
    );
  }
  return <div role="separator" className="mx-3 my-2" style={LINE_STYLE} />;
}

interface SortableDividerProps {
  id: string;
  label: string;
  onLabelChange: (id: string, label: string) => void;
  onRemove: (id: string) => void;
}

/** Edit-mode row of a divider: drag handle, optional name, remove. */
export function SortableDivider({ id, label, onLabelChange, onRemove }: SortableDividerProps) {
  const t = useT();
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id });

  return (
    <div
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition, opacity: isDragging ? 0.5 : 1 }}
      className={`group flex items-center gap-1 rounded-lg px-1 py-0.5 ${isDragging ? 'bg-[var(--color-sidebar-hover)]' : ''}`}
    >
      <button
        {...attributes}
        {...listeners}
        className="shrink-0 cursor-grab touch-none p-1 text-[var(--color-sidebar-text-muted)] hover:text-[var(--color-sidebar-text)] active:cursor-grabbing"
        aria-label={t.nav.moveDivider}
      >
        <GripVertical className="w-3.5 h-3.5" />
      </button>
      <div className="flex min-w-0 flex-1 items-center gap-2">
        <span className="w-3 shrink-0" style={LINE_STYLE} />
        <input
          value={label}
          onChange={(e) => onLabelChange(id, e.target.value)}
          placeholder={t.nav.dividerNamePlaceholder}
          maxLength={40}
          aria-label={t.nav.dividerNamePlaceholder}
          className="min-w-0 flex-1 bg-transparent text-[11px] font-semibold uppercase tracking-wider text-[var(--color-sidebar-text-muted)] outline-none placeholder:normal-case placeholder:font-normal placeholder:tracking-normal placeholder:text-[var(--color-sidebar-text-muted)]/60 focus:text-[var(--color-sidebar-text)]"
        />
        <span className="w-3 shrink-0" style={LINE_STYLE} />
      </div>
      <button
        onClick={() => onRemove(id)}
        className="shrink-0 p-1 text-[var(--color-sidebar-text-muted)] opacity-0 transition-all group-hover:opacity-100 focus:opacity-100 hover:text-danger"
        aria-label={t.nav.removeDivider}
      >
        <X className="w-3.5 h-3.5" />
      </button>
    </div>
  );
}
