import { ChevronDown, Plus, Search, Tag } from 'lucide-react';
import { Button, Card, Spinner, Toggle } from '../ui';
import { DashboardMenu } from '../dashboard/DashboardMenu';
import type { PackageChoice } from '../../hooks/useGoalAddTodo';
import { priorityColor } from '../todo/todoConstants';
import { useT } from '../../i18n';
import type { Todo } from '../../types';

export interface CandidateFilter {
  q: string;
  tag: string;
}

/**
 * „Könnte dazugehören“ (TODO c6ea8f5c): open TODOs without a goal, narrowed by search and tag,
 * assign with one click. The candidates shown go into the relation map as `include` and
 * appear there dashed. The goal page holds filter and data. Since c60bf914 only from the
 * goal's projects; „Alle Projekte zeigen“ searches everywhere and marks TODOs from foreign
 * projects. If the server cannot filter by project (`scoped` false), the switch is missing.
 * Since 85889063 „+“ asks for the work package right away as soon as the TODO's project has packages
 * in the goal: a small menu „Ohne Arbeitspaket“ and the packages in their order, one click suffices.
 */
export function GoalCandidates({
  filter, onFilterChange, candidates, isLoading, suggestions, onAssign, packagesOf = () => [], assigning,
  scoped = false, allProjects = false, onAllProjectsChange, goalProjectIds = [],
}: {
  filter: CandidateFilter;
  onFilterChange: (filter: CandidateFilter) => void;
  candidates: Todo[];
  isLoading: boolean;
  /** Tag suggestions, e.g. `release` and the goal's most frequent tags. */
  suggestions: string[];
  onAssign: (todo: Todo, workPackage: PackageChoice | null) => void;
  /** Packages the TODO fits into (its project), in their order; empty means: „+“ assigns immediately. */
  packagesOf?: (todo: Todo) => PackageChoice[];
  assigning: boolean;
  /** The server knows `/goals/{id}/candidates` and filters by the goal's projects. */
  scoped?: boolean;
  allProjects?: boolean;
  onAllProjectsChange?: (all: boolean) => void;
  /** The goal's projects — empty means: without „Alle Projekte“ there can be no candidates. */
  goalProjectIds?: string[];
}) {
  const t = useT();
  const c = t.goals.candidates;
  const onlyGoalProjects = scoped && !allProjects;
  const foreign = (todo: Todo) => scoped && allProjects && !!todo.projectId && !goalProjectIds.includes(todo.projectId);
  const chip = (active: boolean) => `px-2 py-0.5 rounded-full text-xs border transition-colors ${
    active ? 'border-accent bg-accent-subtle text-accent' : 'border-border text-text-secondary hover:text-text'}`;

  return (
    <section aria-labelledby="goal-candidates" className="space-y-3">
      <div>
        <h2 id="goal-candidates" className="text-sm font-semibold text-text uppercase tracking-wide">{c.title}</h2>
        <p className="text-xs text-text-tertiary mt-0.5">{onlyGoalProjects ? c.subtitleScoped : c.subtitle}</p>
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
        {scoped && onAllProjectsChange && (
          <span className="ml-auto" data-testid="goal-candidates-all-projects">
            <Toggle enabled={allProjects} onChange={onAllProjectsChange} label={c.allProjects} ariaLabel={c.allProjects} />
          </span>
        )}
      </div>
      {isLoading ? (
        <div className="flex justify-center py-4"><Spinner /></div>
      ) : candidates.length === 0 ? (
        <Card>
          <p className="text-sm text-text-secondary">{onlyGoalProjects && goalProjectIds.length === 0 ? c.noProjects : c.empty}</p>
        </Card>
      ) : (
        <Card padding={false} className="border-dashed">
          <ul className="divide-y divide-border">
            {candidates.map((todo) => (
              <li key={todo.id} className="flex items-center gap-3 px-4 py-2" data-testid="goal-candidate">
                <span className={`w-2 h-2 rounded-full shrink-0 ${priorityColor[todo.priority]}`} />
                <div className="flex-1 min-w-0">
                  <p className="text-sm text-text truncate">{todo.title}</p>
                  <p className="text-xs text-text-tertiary truncate">
                    {foreign(todo) && (
                      <span className="mr-1.5 rounded border border-border px-1 text-text-secondary" title={c.otherProjectHint}>
                        {c.otherProject}
                      </span>
                    )}
                    {[todo.projectName, ...todo.tags.map((tag) => `#${tag}`)].filter(Boolean).join(' · ')}
                  </p>
                </div>
                <AssignButton todo={todo} packages={packagesOf(todo)} disabled={assigning} onAssign={onAssign} />
              </li>
            ))}
          </ul>
        </Card>
      )}
    </section>
  );
}

const NO_PACKAGE = '__none__';

/** „+ Zuordnen“: without matching packages a button, otherwise a menu with „Ohne Arbeitspaket“ and the packages. */
function AssignButton({ todo, packages, disabled, onAssign }: {
  todo: Todo;
  packages: PackageChoice[];
  disabled: boolean;
  onAssign: (todo: Todo, workPackage: PackageChoice | null) => void;
}) {
  const t = useT();
  const label = `${t.goals.map.assign}: ${todo.title}`;
  if (packages.length === 0) {
    return (
      <Button size="sm" variant="secondary" disabled={disabled} onClick={() => onAssign(todo, null)} aria-label={label}>
        <Plus className="w-3.5 h-3.5 mr-1" />
        {t.goals.map.assign}
      </Button>
    );
  }
  return (
    <DashboardMenu variant="button" label={label} disabled={disabled}
      icon={(
        <span className="inline-flex items-center gap-1 text-text">
          <Plus className="h-3.5 w-3.5" aria-hidden="true" />
          {t.goals.map.assign}
          <ChevronDown className="h-3 w-3" aria-hidden="true" />
        </span>
      )}
      items={[
        { id: NO_PACKAGE, label: t.goals.outline.noWorkPackage },
        ...packages.map((item) => ({ id: item.id, label: item.title })),
      ]}
      onSelect={(id) => onAssign(todo, id === NO_PACKAGE ? null : packages.find((item) => item.id === id) ?? null)} />
  );
}
