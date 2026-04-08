import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical, Pencil, Trash2, Link2, Terminal, Zap } from 'lucide-react';
import { Badge } from '../ui';
import { useSettingsStore } from '../../stores/settingsStore';
import { useT } from '../../i18n';
import type { BlockDefinition, BlockType } from '../../types';
import type { BadgeVariant } from '../ui/Badge';

const blockTypeVariant: Record<BlockType, BadgeVariant> = {
  simple: 'success',
  agentic: 'success',
  'skill-capability': 'success',
  shell: 'accent',
  approval: 'warning',
  'human-input': 'warning',
  'sub-workflow': 'accent',
  retry: 'default',
  foreach: 'default',
  while: 'default',
  conditional: 'default',
  switch: 'default',
};

interface SortableBlockProps {
  id: string;
  index: number;
  block: BlockDefinition;
  onEdit: (index: number) => void;
  onRemove: (index: number) => void;
}

export function SortableBlock({ id, index, block, onEdit, onRemove }: SortableBlockProps) {
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
      className={`flex items-start gap-3 px-5 py-4 group cursor-pointer hover:bg-surface/50 transition-colors ${
        isDragging ? 'bg-surface-raised shadow-lg rounded-xl' : ''
      }`}
      onClick={() => onEdit(index)}
    >
      <button
        {...attributes}
        {...listeners}
        className="pt-1 text-text-tertiary hover:text-text cursor-grab active:cursor-grabbing touch-none"
        aria-label="Drag to reorder"
        onClick={(e) => e.stopPropagation()}
      >
        <GripVertical className="w-4 h-4" />
      </button>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 mb-1">
          <span className="text-xs font-mono px-2 py-0.5 rounded-sm bg-surface text-text-secondary">
            #{index + 1}
          </span>
          <Badge variant={blockTypeVariant[block.type] || 'accent'}>{t.workflows.blockTypes[block.type]}</Badge>
          {block.skillIds && block.skillIds.length > 0 && (
            <span className="inline-flex items-center gap-0.5 text-[10px] text-accent">
              <Zap className="w-3 h-3" />
              +{block.skillIds.length}
            </span>
          )}
        </div>
        {advancedMode ? (
          <pre className="text-xs text-text-secondary font-mono bg-surface rounded-lg p-3 mt-2 overflow-x-auto">
            {JSON.stringify(block.config, null, 2)}
          </pre>
        ) : block.type === 'shell' ? (
          <p className="text-sm text-text-secondary truncate flex items-center gap-1.5">
            <Terminal className="w-3.5 h-3.5 text-accent shrink-0" />
            <code className="font-mono text-xs">{String(block.config.command || '')}</code>
          </p>
        ) : block.type === 'sub-workflow' ? (
          <p className="text-sm text-text-secondary truncate flex items-center gap-1.5">
            <Link2 className="w-3.5 h-3.5 text-accent shrink-0" />
            {String(block.config.workflow_id || t.workflows.selectSubWorkflow)}
          </p>
        ) : (
          <p className="text-sm text-text-secondary truncate">
            {'command' in block.config
              ? String(block.config.command)
              : 'capability' in block.config
                ? String(block.config.capability)
                : 'goal' in block.config
                  ? String(block.config.goal)
                  : 'prompt' in block.config
                    ? String(block.config.prompt)
                    : 'question' in block.config
                      ? String(block.config.question)
                      : JSON.stringify(block.config)}
          </p>
        )}
      </div>
      <button
        onClick={(e) => { e.stopPropagation(); onEdit(index); }}
        className="p-1.5 rounded-lg text-text-tertiary hover:text-accent sm:opacity-0 sm:group-hover:opacity-100 transition-all"
        aria-label={t.common.edit}
      >
        <Pencil className="w-4 h-4" />
      </button>
      <button
        onClick={(e) => { e.stopPropagation(); onRemove(index); }}
        className="p-1.5 rounded-lg text-text-tertiary hover:text-danger sm:opacity-0 sm:group-hover:opacity-100 transition-all"
        aria-label={t.common.delete}
      >
        <Trash2 className="w-4 h-4" />
      </button>
    </div>
  );
}
