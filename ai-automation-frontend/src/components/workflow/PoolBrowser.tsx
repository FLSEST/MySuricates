import { useState } from 'react';
import { Search, Tag } from 'lucide-react';
import { Card, Input, Spinner } from '../ui';
import { useT } from '../../i18n';
import { useI18nStore } from '../../i18n';
import { usePoolWorkflows } from '../../hooks/usePool';
import { categoryLabel, resolveTemplateName } from '../../utils/templateUtils';
import { ExecutionBadges } from '../shared/ExecutionBadges';
import type { PoolWorkflowEntry } from '../../types';

interface PoolBrowserProps {
  onSelect: (entry: PoolWorkflowEntry) => void;
  /** `simple` hides build details (step count, execution kinds). */
  variant?: 'pro' | 'simple';
}

export function PoolBrowser({ onSelect, variant = 'pro' }: PoolBrowserProps) {
  const simple = variant === 'simple';
  const t = useT();
  // repo and external both say "not just anywhere" (warning); a value this build does not know shows as is
  const scopeBadge = (scope: string) => {
    switch (scope) {
      case 'repo': return { label: t.pool.scopeRepo, hint: t.pool.scopeRepoHint, className: 'bg-warning-subtle text-warning' };
      case 'portable': return { label: t.pool.scopePortable, hint: t.pool.scopePortableHint, className: 'bg-info-subtle text-info' };
      case 'external': return { label: t.pool.scopeExternal, hint: t.pool.scopeExternalHint, className: 'bg-warning-subtle text-warning' };
      default: return { label: scope, hint: '', className: 'bg-surface-hover text-text-secondary' };
    }
  };
  const locale = useI18nStore((s) => s.locale);
  const { data: entries, isLoading } = usePoolWorkflows();
  const [search, setSearch] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);

  const allCategories = Array.from(
    new Set((entries ?? []).map((e) => e.category).filter(Boolean) as string[]),
  ).sort((a, b) => categoryLabel(t, a).localeCompare(categoryLabel(t, b), locale));

  const filtered = (entries ?? []).filter((entry) => {
    if (selectedCategory && entry.category !== selectedCategory) return false;
    if (!search.trim()) return true;
    const name = resolveTemplateName(entry.name, locale).toLowerCase();
    const desc = resolveTemplateName(entry.description, locale).toLowerCase();
    const tags = entry.tags.join(' ').toLowerCase();
    const q = search.toLowerCase();
    return name.includes(q) || desc.includes(q) || tags.includes(q);
  });

  return (
    <div className="space-y-4">
      <div>
        <h3 className="text-lg font-semibold text-text">{t.pool.title}</h3>
        <p className="text-sm text-text-secondary mt-1">{t.pool.subtitle}</p>
      </div>

      <Input
        placeholder={t.pool.searchPlaceholder}
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        icon={<Search className="w-4 h-4 text-text-tertiary" />}
      />

      {allCategories.length > 1 && (
        <div className="flex flex-wrap gap-1.5">
          <button
            onClick={() => setSelectedCategory(null)}
            className={`px-2.5 py-1 rounded-full text-xs font-medium transition-colors ${
              !selectedCategory
                ? 'bg-accent text-text-inverse'
                : 'bg-surface-raised text-text-secondary hover:text-text'
            }`}
          >
            {t.common.all}
          </button>
          {allCategories.map((cat) => (
            <button
              key={cat}
              onClick={() => setSelectedCategory(selectedCategory === cat ? null : cat)}
              className={`px-2.5 py-1 rounded-full text-xs font-medium transition-colors ${
                selectedCategory === cat
                  ? 'bg-accent text-text-inverse'
                  : 'bg-surface-raised text-text-secondary hover:text-text'
              }`}
            >
              {categoryLabel(t, cat)}
            </button>
          ))}
        </div>
      )}

      {isLoading ? (
        <div className="flex justify-center py-8">
          <Spinner text={t.common.loading} />
        </div>
      ) : filtered.length === 0 ? (
        <div className="py-8 text-center text-sm text-text-tertiary">
          {entries?.length === 0 ? t.pool.empty : t.common.noResults}
        </div>
      ) : (
        <div className="max-h-[60vh] overflow-y-auto grid grid-cols-1 sm:grid-cols-2 gap-3">
          {filtered.map((entry) => (
            <Card
              key={entry.id}
              className="cursor-pointer hover:border-border-strong transition-colors"
              onClick={() => onSelect(entry)}
            >
              <div className="flex items-center gap-3 mb-2">
                <span className="text-2xl">{entry.icon}</span>
                <div className="min-w-0 flex-1">
                  <h4 className="font-semibold text-text truncate">
                    {resolveTemplateName(entry.name, locale)}
                  </h4>
                  <div className="flex items-center gap-2">
                    {!simple && (
                      <>
                        <span className="text-xs text-text-tertiary">
                          {entry.blocks.length} {t.workflows.blocks.toLowerCase()}
                        </span>
                        <ExecutionBadges blocks={entry.blocks} />
                      </>
                    )}
                    {entry.version && (
                      <span className="text-xs text-text-tertiary">v{entry.version}</span>
                    )}
                    {entry.scope && (
                      <span
                        data-testid={`pool-scope-${entry.id}`}
                        title={scopeBadge(entry.scope).hint}
                        className={`px-1.5 py-0.5 rounded-sm text-[10px] font-medium ${scopeBadge(entry.scope).className}`}
                      >
                        {scopeBadge(entry.scope).label}
                      </span>
                    )}
                  </div>
                </div>
              </div>
              <p className="text-xs text-text-secondary mb-2">
                {resolveTemplateName(entry.description, locale)}
              </p>
              {entry.tags.length > 0 && (
                <div className="flex flex-wrap gap-1">
                  {entry.tags.map((tag) => (
                    <span
                      key={tag}
                      className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded-sm text-[10px] font-medium bg-surface-raised text-text-tertiary"
                    >
                      <Tag className="w-2.5 h-2.5" />
                      {tag}
                    </span>
                  ))}
                </div>
              )}
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
