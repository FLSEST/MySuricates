import { Plus, Search, Tag } from 'lucide-react';
import { Button, Card, Spinner } from '../ui';
import { priorityColor } from '../todo/todoConstants';
import { useT } from '../../i18n';
import type { Todo } from '../../types';

export interface CandidateFilter {
  q: string;
  tag: string;
}

/**
 * „Könnte dazugehören“ (TODO c6ea8f5c): offene TODOs ohne Ziel, per Suche und Tag eingegrenzt,
 * mit einem Klick zuordnen. Die gezeigten Kandidaten gehen als `include` in die Relationskarte und
 * erscheinen dort gestrichelt. Filter und Daten hält die Zielseite.
 */
export function GoalCandidates({ filter, onFilterChange, candidates, isLoading, suggestions, onAssign, assigning }: {
  filter: CandidateFilter;
  onFilterChange: (filter: CandidateFilter) => void;
  candidates: Todo[];
  isLoading: boolean;
  /** Tag-Vorschläge, etwa `release` und die häufigsten Tags des Ziels. */
  suggestions: string[];
  onAssign: (todoId: string) => void;
  assigning: boolean;
}) {
  const t = useT();
  const c = t.goals.candidates;
  const chip = (active: boolean) => `px-2 py-0.5 rounded-full text-xs border transition-colors ${
    active ? 'border-accent bg-accent-subtle text-accent' : 'border-border text-text-secondary hover:text-text'}`;

  return (
    <section aria-labelledby="goal-candidates" className="space-y-3">
      <div>
        <h2 id="goal-candidates" className="text-sm font-semibold text-text uppercase tracking-wide">{c.title}</h2>
        <p className="text-xs text-text-tertiary mt-0.5">{c.subtitle}</p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <label className="relative flex-1 min-w-[12rem] max-w-sm">
          <span className="sr-only">{c.search}</span>
          <Search className="w-3.5 h-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-text-tertiary" />
          <input type="search" className="input-base w-full pl-8" placeholder={c.search} value={filter.q}
            onChange={(e) => onFilterChange({ ...filter, q: e.target.value })} />
        </label>
        <label className="relative w-40">
          <span className="sr-only">{c.tag}</span>
          <Tag className="w-3.5 h-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-text-tertiary" />
          <input className="input-base w-full pl-8" placeholder={c.tag} value={filter.tag}
            onChange={(e) => onFilterChange({ ...filter, tag: e.target.value })} />
        </label>
        <span className="text-xs text-text-tertiary">{c.suggestions}:</span>
        <button type="button" className={chip(!filter.tag)} onClick={() => onFilterChange({ ...filter, tag: '' })}>{c.allTags}</button>
        {suggestions.map((tag) => (
          <button key={tag} type="button" className={chip(filter.tag === tag)} aria-pressed={filter.tag === tag}
            onClick={() => onFilterChange({ ...filter, tag })}>
            {tag}
          </button>
        ))}
      </div>
      {isLoading ? (
        <div className="flex justify-center py-4"><Spinner /></div>
      ) : candidates.length === 0 ? (
        <Card><p className="text-sm text-text-secondary">{c.empty}</p></Card>
      ) : (
        <Card padding={false} className="border-dashed">
          <ul className="divide-y divide-border">
            {candidates.map((todo) => (
              <li key={todo.id} className="flex items-center gap-3 px-4 py-2" data-testid="goal-candidate">
                <span className={`w-2 h-2 rounded-full shrink-0 ${priorityColor[todo.priority]}`} />
                <div className="flex-1 min-w-0">
                  <p className="text-sm text-text truncate">{todo.title}</p>
                  <p className="text-xs text-text-tertiary truncate">
                    {[todo.projectName, ...todo.tags.map((tag) => `#${tag}`)].filter(Boolean).join(' · ')}
                  </p>
                </div>
                <Button size="sm" variant="secondary" disabled={assigning} onClick={() => onAssign(todo.id)}
                  aria-label={`${t.goals.map.assign}: ${todo.title}`}>
                  <Plus className="w-3.5 h-3.5 mr-1" />
                  {t.goals.map.assign}
                </Button>
              </li>
            ))}
          </ul>
        </Card>
      )}
    </section>
  );
}
