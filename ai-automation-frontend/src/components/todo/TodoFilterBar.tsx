import { Search, List, LayoutGrid } from 'lucide-react';
import { useT } from '../../i18n';
import { STATUSES, SOURCES } from './todoConstants';
import type { useTodoFilters } from '../../hooks/useTodoFilters';
import type { Project, TodoSource } from '../../types';

type TodoFilters = ReturnType<typeof useTodoFilters>;

/** Status tabs (with the archive set apart), source, project, search and list/board toggle. */
export function TodoFilterBar({ filters, projects }: {
  filters: Pick<TodoFilters,
    'statusFilter' | 'setStatusFilter' | 'sourceFilter' | 'setSourceFilter' | 'projectFilter' | 'setProjectFilter'
    | 'search' | 'setSearch' | 'viewMode' | 'setViewMode'>;
  projects: Project[] | undefined;
}) {
  const t = useT();
  const {
    statusFilter, setStatusFilter, sourceFilter, setSourceFilter, projectFilter, setProjectFilter,
    search, setSearch, viewMode, setViewMode,
  } = filters;

  return (
    <div className="flex flex-wrap items-center gap-3">
      {/* Status tabs */}
      <div className="flex items-center gap-1.5">
        <button
          onClick={() => setStatusFilter('ACTIVE')}
          className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
            statusFilter === 'ACTIVE'
              ? 'bg-accent text-text-inverse'
              : 'text-text-secondary hover:text-text hover:bg-surface-raised'
          }`}
        >
          {t.todos.activeStatuses}
        </button>
        <button
          onClick={() => setStatusFilter('ALL')}
          className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
            statusFilter === 'ALL'
              ? 'bg-accent text-text-inverse'
              : 'text-text-secondary hover:text-text hover:bg-surface-raised'
          }`}
        >
          {t.todos.allStatuses}
        </button>
        {STATUSES.map((s) => (
          <button
            key={s}
            onClick={() => setStatusFilter(s)}
            className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
              statusFilter === s
                ? 'bg-accent text-text-inverse'
                : 'text-text-secondary hover:text-text hover:bg-surface-raised'
            }`}
          >
            {t.todos.statuses[s]}
          </button>
        ))}
        {/* Archive tab — separated; ARCHIVED is hidden from the default views */}
        <span className="w-px h-4 bg-border mx-1" aria-hidden />
        <button
          onClick={() => setStatusFilter('ARCHIVED')}
          className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
            statusFilter === 'ARCHIVED'
              ? 'bg-accent text-text-inverse'
              : 'text-text-tertiary hover:text-text hover:bg-surface-raised'
          }`}
        >
          {t.todos.statuses.ARCHIVED}
        </button>
      </div>

      {/* Source filter */}
      <select
        className="input-base text-xs py-1.5 w-32"
        value={sourceFilter}
        onChange={(e) => setSourceFilter(e.target.value as TodoSource | 'ALL')}
      >
        <option value="ALL">{t.todos.allSources}</option>
        {SOURCES.map((s) => (
          <option key={s} value={s}>{t.todos.sources[s]}</option>
        ))}
      </select>

      {/* Project filter */}
      <select
        className="input-base text-xs py-1.5 w-40"
        value={projectFilter}
        onChange={(e) => setProjectFilter(e.target.value)}
      >
        <option value="">{t.todos.allProjects}</option>
        {projects?.map((p) => (
          <option key={p.id} value={p.id}>{p.name}</option>
        ))}
      </select>

      {/* Search */}
      <div className="relative flex-1 min-w-[180px] max-w-xs">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-text-tertiary pointer-events-none" />
        <input
          type="text"
          className="input-base pl-9 text-xs py-1.5 w-full"
          placeholder={t.common.search}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>

      {/* View toggle */}
      <div className="flex items-center gap-1 ml-auto">
        <button
          onClick={() => setViewMode('list')}
          className={`p-1.5 rounded-lg transition-colors ${
            viewMode === 'list' ? 'bg-accent text-text-inverse' : 'text-text-tertiary hover:text-text hover:bg-surface-raised'
          }`}
          title={t.todos.viewList}
        >
          <List className="w-4 h-4" />
        </button>
        <button
          onClick={() => setViewMode('board')}
          className={`p-1.5 rounded-lg transition-colors ${
            viewMode === 'board' ? 'bg-accent text-text-inverse' : 'text-text-tertiary hover:text-text hover:bg-surface-raised'
          }`}
          title={t.todos.viewBoard}
        >
          <LayoutGrid className="w-4 h-4" />
        </button>
      </div>
    </div>
  );
}
