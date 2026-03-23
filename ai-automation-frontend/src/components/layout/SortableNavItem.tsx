import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical, X } from 'lucide-react';
import type { ComponentType } from 'react';

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
      className={`flex items-center gap-1 px-1 py-1 rounded-lg group ${isDragging ? 'bg-white/10' : ''}`}
    >
      <button
        {...attributes}
        {...listeners}
        className="p-1 text-[#5a4e44] hover:text-[#8a7d6e] cursor-grab active:cursor-grabbing touch-none shrink-0"
        aria-label="Verschieben"
      >
        <GripVertical className="w-3.5 h-3.5" />
      </button>
      <item.icon style={{ width: 16, height: 16 }} className="text-[#8a7d6e] shrink-0" />
      <span className="flex-1 text-sm text-[#8a7d6e] truncate">{item.label}</span>
      <button
        onClick={() => onHide(item.to)}
        className="p-1 text-[#5a4e44] hover:text-[#c0392b] shrink-0 opacity-0 group-hover:opacity-100 transition-opacity"
        aria-label="Ausblenden"
      >
        <X className="w-3.5 h-3.5" />
      </button>
    </div>
  );
}
