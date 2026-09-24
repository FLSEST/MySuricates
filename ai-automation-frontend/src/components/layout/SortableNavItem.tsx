import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical, X } from 'lucide-react';
import type { ComponentType } from 'react';
import { useT } from '../../i18n';

interface NavItemDef {
  to: string;
  icon: ComponentType<{ className?: string; style?: React.CSSProperties }>;
  label: string;
}

interface SortableNavItemProps {
  id: string;
  item: NavItemDef;
  onHide: (to: string) => void;
}

export function SortableNavItem({ id, item, onHide }: SortableNavItemProps) {
  const t = useT();
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`group flex items-center gap-1 rounded-lg px-1 py-1 ${isDragging ? 'bg-[var(--color-sidebar-hover)]' : ''}`}
    >
      <button
        {...attributes}
        {...listeners}
        className="shrink-0 cursor-grab touch-none p-1 text-[var(--color-sidebar-text-muted)] hover:text-[var(--color-sidebar-text)] active:cursor-grabbing"
        aria-label={t.nav.movePage.replace('{name}', item.label)}
      >
        <GripVertical className="w-3.5 h-3.5" />
      </button>
      <item.icon style={{ width: 16, height: 16 }} className="shrink-0 text-[var(--color-sidebar-text-muted)]" />
      <span className="flex-1 truncate text-sm text-[var(--color-sidebar-text)]">{item.label}</span>
      <button
        onClick={() => onHide(item.to)}
        className="shrink-0 p-1 text-[var(--color-sidebar-text-muted)] opacity-0 transition-all group-hover:opacity-100 hover:text-danger"
        aria-label={t.nav.hidePage.replace('{name}', item.label)}
      >
        <X className="w-3.5 h-3.5" />
      </button>
    </div>
  );
}
