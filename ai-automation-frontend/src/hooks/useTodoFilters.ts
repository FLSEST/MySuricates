import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { usePersistedState } from './usePersistedState';
import { ACTIVE_STATUSES, SEARCH_DEBOUNCE_MS, type TodoStatusFilter, type ViewMode } from '../components/todo/todoConstants';
import type { TodoSource } from '../types';

/**
 * Filter-, Ansichts- und Seitenzustand der TODO-Seite (persistiert, Suche entprellt) und die
 * daraus gebauten Server-Parameter: `filterParams` ohne Status (das Board ergänzt ihn je Spalte),
 * `apiParams` mit Status für die Liste. Ein `?projectId=` in der URL setzt den Projektfilter.
 */
export function useTodoFilters() {
  // Default: what is actually being worked on. With a 90 %-DONE backlog, ALL is a long,
  // useless page — ALL stays selectable, and a persisted choice is never overwritten.
  const [statusFilter, setStatusFilter] = usePersistedState<TodoStatusFilter>('todos-status-filter', 'ACTIVE');
  const [sourceFilter, setSourceFilter] = usePersistedState<TodoSource | 'ALL'>('todos-source-filter', 'ALL');
  const [projectFilter, setProjectFilter] = usePersistedState('todos-project-filter', '');

  // Deep-link from the cockpit: ?projectId=… focuses that project (additive — persisted filter wins when absent)
  const [searchParams] = useSearchParams();
  useEffect(() => {
    const pid = searchParams.get('projectId');
    if (pid) setProjectFilter(pid);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  const [search, setSearch] = usePersistedState('todos-search', '');
  const [debouncedSearch, setDebouncedSearch] = useState(search);
  const [tagFilter, setTagFilter] = usePersistedState('todos-tag-filter', '');

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [search]);

  // View mode
  const [viewMode, setViewMode] = usePersistedState<ViewMode>('todos-view-mode', 'list');

  const [page, setPage] = useState(0);

  // Filters without the status — the board adds the status per column
  const filterParams = useMemo(() => {
    const params: Record<string, string> = {};
    if (sourceFilter !== 'ALL') params.source = sourceFilter;
    if (projectFilter) params.projectId = projectFilter;
    if (tagFilter) params.tag = tagFilter;
    if (debouncedSearch) params.q = debouncedSearch;
    return params;
  }, [sourceFilter, projectFilter, tagFilter, debouncedSearch]);

  // Build server-side filter params for the list view
  const apiParams = useMemo<Record<string, string | string[]>>(() => {
    if (statusFilter === 'ALL') return filterParams;
    if (statusFilter === 'ACTIVE') return { ...filterParams, status: ACTIVE_STATUSES };
    return { ...filterParams, status: statusFilter };
  }, [filterParams, statusFilter]);

  // Reset page on filter or view mode change
  useEffect(() => { setPage(0); }, [apiParams, viewMode]);

  return {
    searchParams,
    statusFilter, setStatusFilter,
    sourceFilter, setSourceFilter,
    projectFilter, setProjectFilter,
    search, setSearch,
    tagFilter, setTagFilter,
    viewMode, setViewMode,
    page, setPage,
    filterParams, apiParams,
  };
}
