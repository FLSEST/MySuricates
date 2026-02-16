import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical, Trash2 } from 'lucide-react';
import { Badge } from '../ui';
import { useSettingsStore } from '../../stores/settingsStore';
import { useT } from '../../i18n';
import type { BlockDefinition } from '../../types';

interface SortableBlockProps {
  id: string;
  index: number;
  block: BlockDefinition;
  onRemove: (index: number) => void;
}

export function SortableBlock({ id, index, block, onRemove }: SortableBlockProps) {
  const t = useT();
  const advancedMode = useSettingsStore((s) => s.advancedMode);
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
    zIndex: isDragging ? 50 : undefined,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`flex items-start gap-3 px-5 py-4 group ${
        isDragging ? 'bg-surface-raised shadow-lg rounded-xl' : ''
      }`}
    >
      <button
        {...attributes}
        {...listeners}
        className="pt-1 text-text-tertiary hover:text-text cursor-grab active:cursor-grabbing touch-none"
        aria-label="Drag to reorder"
      >
        <GripVertical className="w-4 h-4" />
      </button>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 mb-1">
          <span className="text-xs font-mono px-2 py-0.5 rounded bg-surface text-text-secondary">
            #{index + 1}
          </span>
          <Badge variant="accent">{t.workflows.blockTypes[block.type]}</Badge>
        </div>
        {advancedMode ? (
          <pre className="text-xs text-text-secondary font-mono bg-surface rounded-lg p-3 mt-2 overflow-x-auto">
            {JSON.stringify(block.config, null, 2)}
          </pre>
        ) : (
          <p className="text-sm text-text-secondary truncate">
            {'command' in block.config
              ? String(block.config.command)
              : 'capability' in block.config
                ? String(block.config.capability)
                : JSON.stringify(block.config)}
          </p>
        )}
      </div>
      <button
        onClick={() => onRemove(index)}
        className="p-1.5 rounded-lg text-text-tertiary hover:text-danger opacity-0 group-hover:opacity-100 transition-all"
        aria-label={t.common.delete}
      >
        <Trash2 className="w-4 h-4" />
      </button>
    </div>
  );
}
