import { X } from 'lucide-react';
import { useT } from '../../i18n';
import { MAX_VISIBLE_TAGS } from './todoConstants';

/**
 * Tag chips over the list: a click filters by the tag (again: clears it). Beyond
 * `MAX_VISIBLE_TAGS` the rest folds away; the page holds `expanded`, so it survives a reload spinner.
 */
export function TodoTagChips({ tags, tagFilter, onTagFilterChange, expanded, onExpandedChange }: {
  tags: string[];
  tagFilter: string;
  onTagFilterChange: (tag: string) => void;
  expanded: boolean;
  onExpandedChange: (expanded: boolean) => void;
}) {
  const t = useT();
  if (tags.length === 0) return null;

  const visibleTags = expanded ? tags : tags.slice(0, MAX_VISIBLE_TAGS);
  const hasMoreTags = tags.length > MAX_VISIBLE_TAGS;

  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {visibleTags.map((tag) => (
        <button
          key={tag}
          onClick={() => onTagFilterChange(tagFilter === tag ? '' : tag)}
          className={`px-2.5 py-1 rounded-full text-xs font-medium transition-colors border ${
            tagFilter === tag
              ? 'bg-accent text-text-inverse border-accent'
              : 'bg-surface-raised text-text-secondary border-border hover:border-border-strong'
          }`}
        >
          {tag}
        </button>
      ))}
      {hasMoreTags && (
        <button
          onClick={() => onExpandedChange(!expanded)}
          className="px-2.5 py-1 rounded-full text-xs font-medium text-accent hover:bg-accent/10 transition-colors"
        >
          {expanded ? t.todos.showLessTags : `${t.todos.showMoreTags} (+${tags.length - MAX_VISIBLE_TAGS})`}
        </button>
      )}
      {tagFilter && (
        <button
          onClick={() => onTagFilterChange('')}
          className="px-2 py-1 rounded-full text-xs text-text-tertiary hover:text-text transition-colors"
        >
          <X className="w-3 h-3" />
        </button>
      )}
    </div>
  );
}
