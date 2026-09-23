import { useMemo, useState } from 'react';
import { Play } from 'lucide-react';
import { WorkflowIcon } from '../../utils/workflowIcon';
import { SealStateIcon } from './SealStateIcon';
import { useSealStatus } from '../../hooks/useSealStatus';
import type { Workflow } from '../../types';
import type { TranslationKeys } from '../../i18n';
import type { useNavigate } from 'react-router-dom';

const CATEGORY_ORDER = [
  'Productivity',
  'Dev',
  'Content',
  'Analysis',
  'Ops',
  'Data',
  'Integration',
  'Other',
] as const;

interface DiscoveryViewProps {
  t: TranslationKeys;
  workflows: Workflow[];
  search: string;
  onExecute: (w: Workflow) => void;
  navigate: ReturnType<typeof useNavigate>;
}

export function DiscoveryView({ t, workflows, search, onExecute, navigate }: DiscoveryViewProps) {
  const [activeCategory, setActiveCategory] = useState<string | null>(null);
  const [activeTag, setActiveTag] = useState<string | null>(null);

  const categoryLabel = (key: string | null | undefined): string => {
    if (!key) return t.workflows.tagsUncategorized;
    const map = t.workflows.tagsCategories as Record<string, string>;
    return map[key] ?? key;
  };

  const categoryCounts = useMemo(() => {
    const counts = new Map<string, number>();
    for (const w of workflows) {
      const key = w.category ?? 'Other';
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    return counts;
  }, [workflows]);

  const tagCounts = useMemo(() => {
    const counts = new Map<string, number>();
    for (const w of workflows) {
      for (const tag of w.tags ?? []) counts.set(tag, (counts.get(tag) ?? 0) + 1);
    }
    return counts;
  }, [workflows]);

  const visibleCategories = useMemo(
    () => CATEGORY_ORDER.filter((c) => (categoryCounts.get(c) ?? 0) > 0),
    [categoryCounts],
  );
  const visibleTags = useMemo(
    () =>
      Array.from(tagCounts.entries())
        .sort((a, b) => b[1] - a[1])
        .slice(0, 30)
        .map(([tag, count]) => ({ tag, count })),
    [tagCounts],
  );

  const filtered = useMemo(() => {
    let list = workflows;
    if (activeCategory) {
      list = list.filter((w) => (w.category ?? 'Other') === activeCategory);
    }
    if (activeTag) {
      list = list.filter((w) => (w.tags ?? []).includes(activeTag));
    }
    if (search) {
      const q = search.toLowerCase();
      list = list.filter(
        (w) =>
          w.name.toLowerCase().includes(q) ||
          (w.description?.toLowerCase().includes(q) ?? false) ||
          (w.tags ?? []).some((t) => t.toLowerCase().includes(q)),
      );
    }
    return [...list].sort((a, b) => a.name.localeCompare(b.name));
  }, [workflows, activeCategory, activeTag, search]);

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-1.5">
        <CategoryChip
          label={t.common.all}
          active={activeCategory === null}
          onClick={() => setActiveCategory(null)}
          count={workflows.length}
        />
        {visibleCategories.map((c) => (
          <CategoryChip
            key={c}
            label={categoryLabel(c)}
            active={activeCategory === c}
            onClick={() => setActiveCategory(activeCategory === c ? null : c)}
            count={categoryCounts.get(c) ?? 0}
          />
        ))}
      </div>

      {visibleTags.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {visibleTags.map(({ tag, count }) => (
            <button
              key={tag}
              onClick={() => setActiveTag(activeTag === tag ? null : tag)}
              className={`text-[10px] px-2 py-0.5 rounded-sm transition-colors ${
                activeTag === tag
                  ? 'bg-accent/20 text-accent border border-accent/30'
                  : 'bg-surface-raised text-text-tertiary hover:text-text border border-transparent'
              }`}
            >
              {tag} <span className="opacity-60">{count}</span>
            </button>
          ))}
        </div>
      )}

      <div className="space-y-1">
        {filtered.map((w) => (
          <div
            key={w.id}
            onClick={() => navigate(`/workflows/${w.id}`)}
            className="group flex items-center gap-3 px-3 py-2 rounded-lg border border-transparent hover:bg-surface-raised hover:border-border cursor-pointer transition-colors"
          >
            <span className="shrink-0">
              <WorkflowIcon icon={w.icon} className="w-4 h-4" />
            </span>
            <div className="flex-1 min-w-0">
              <div className="text-sm text-text truncate">{w.name}</div>
              {w.description && (
                <div className="text-xs text-text-tertiary truncate">{w.description}</div>
              )}
            </div>
            <DiscoverySealIcon workflow={w} />
            {(w.tags ?? []).slice(0, 3).map((tag) => (
              <span
                key={tag}
                className="hidden md:inline text-[10px] px-1.5 py-0.5 rounded-sm bg-accent/10 text-accent"
              >
                {tag}
              </span>
            ))}
            <button
              onClick={(e) => { e.stopPropagation(); onExecute(w); }}
              className="sm:opacity-0 sm:group-hover:opacity-100 p-1 rounded-sm hover:bg-accent/20 text-accent"
              title={t.workflows.execute}
              aria-label={t.workflows.execute}
            >
              <Play className="w-3.5 h-3.5" />
            </button>
          </div>
        ))}
        {filtered.length === 0 && (
          <div className="px-3 py-6 text-center text-sm text-text-tertiary">
            {t.commandPalette.noResults}
          </div>
        )}
      </div>
    </div>
  );
}

interface CategoryChipProps {
  label: string;
  active: boolean;
  onClick: () => void;
  count: number;
}

function DiscoverySealIcon({ workflow }: { workflow: Workflow }) {
  const status = useSealStatus(workflow.id);
  return <SealStateIcon sealed={workflow.sealed} status={status} className="w-3.5 h-3.5" />;
}

function CategoryChip({ label, active, onClick, count }: CategoryChipProps) {
  return (
    <button
      onClick={onClick}
      className={`text-xs px-2.5 py-1 rounded-full transition-colors ${
        active
          ? 'bg-accent text-text-inverse'
          : 'bg-surface-raised text-text-secondary hover:bg-surface-raised/70'
      }`}
    >
      {label} <span className="opacity-60">{count}</span>
    </button>
  );
}
