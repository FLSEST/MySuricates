import { ChevronDown, ChevronUp, Check, Pencil, Trash2 } from 'lucide-react';
import { Button } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { formatTimestamp } from '../../utils/localeFormat';
import { providerName } from '../shared/aiProviderStatus';
import type { WorkflowRun, WorkflowMemory } from '../../types';

interface WorkflowMemoryItemProps {
  memory: WorkflowMemory;
  /** Shown when the memory has no title (its version number). */
  fallbackTitle: string;
  runs?: Omit<WorkflowRun, 'steps'>[];
  expanded: boolean;
  onToggleExpanded: () => void;
  onActivate: () => void;
  activating: boolean;
  onEdit: () => void;
  onDelete: () => void;
}

/** One memory entry: title, active badge, origin, tags, collapsible content and its actions. */
export function WorkflowMemoryItem({
  memory: mem, fallbackTitle, runs, expanded, onToggleExpanded, onActivate, activating, onEdit, onDelete,
}: WorkflowMemoryItemProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const sourceRuns = runs?.filter(r => mem.sourceRunIds.includes(r.id)) ?? [];

  return (
    <div
      className={`rounded-lg border p-3 transition-colors ${
        mem.active
          ? 'border-accent/40 bg-accent/5'
          : 'border-border bg-surface'
      }`}
    >
      {/* Header */}
      <div className="flex items-start justify-between gap-2">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium text-text truncate">
              {mem.title || fallbackTitle}
            </span>
            {mem.active && (
              <span className="shrink-0 inline-flex items-center gap-1 px-1.5 py-0.5 rounded-sm text-[10px] font-medium bg-accent/10 text-accent">
                <Check className="w-3 h-3" />
                {t.memory.active}
              </span>
            )}
          </div>
          <div className="flex items-center gap-3 mt-1 text-xs text-text-tertiary flex-wrap">
            <span>{formatTimestamp(mem.createdAt, locale)}</span>
            {mem.provider && <span>{t.memory.provider}: {providerName(mem.provider)}</span>}
            {mem.sourceRunIds.length > 0 && (
              <span className="flex items-center gap-1 flex-wrap">
                <span>{t.memory.sourceRuns}:</span>
                {sourceRuns.length > 0
                  ? sourceRuns.map(r => (
                      <span key={r.id} className="px-1.5 py-0.5 rounded-sm bg-surface-raised text-[10px]">
                        {formatTimestamp(r.startedAt, locale)}
                      </span>
                    ))
                  : <span>{t.memory.runCount.replace('{count}', String(mem.sourceRunIds.length))}</span>
                }
              </span>
            )}
          </div>
          {mem.tags.length > 0 && (
            <div className="flex flex-wrap gap-1 mt-1.5">
              {mem.tags.map((tag) => (
                <span
                  key={tag}
                  className="px-1.5 py-0.5 rounded-sm text-[10px] font-medium bg-surface-raised text-text-secondary"
                >
                  {tag}
                </span>
              ))}
            </div>
          )}
        </div>
        <button
          type="button"
          onClick={onToggleExpanded}
          aria-expanded={expanded}
          aria-label={expanded ? t.common.collapse : t.common.expand}
          title={expanded ? t.common.collapse : t.common.expand}
          className="p-1 rounded-sm text-text-tertiary hover:text-text transition-colors"
        >
          {expanded ? (
            <ChevronUp className="w-4 h-4" />
          ) : (
            <ChevronDown className="w-4 h-4" />
          )}
        </button>
      </div>

      {/* Expanded preview */}
      {expanded && (
        <div className="mt-3 pt-3 border-t border-border">
          <pre className="text-xs text-text-secondary whitespace-pre-wrap font-mono bg-surface rounded-lg p-3 max-h-60 overflow-y-auto">
            {mem.content}
          </pre>
        </div>
      )}

      {/* Actions */}
      <div className="flex items-center gap-2 mt-2">
        {!mem.active && (
          <Button
            size="sm"
            variant="secondary"
            onClick={onActivate}
            loading={activating}
          >
            {t.memory.activate}
          </Button>
        )}
        <Button
          size="sm"
          variant="ghost"
          icon={<Pencil className="w-3 h-3" />}
          onClick={onEdit}
          aria-label={t.common.edit}
          title={t.common.edit}
        />
        <Button
          size="sm"
          variant="ghost"
          icon={<Trash2 className="w-3 h-3" />}
          onClick={onDelete}
          aria-label={t.common.delete}
          title={t.common.delete}
        />
      </div>
    </div>
  );
}
