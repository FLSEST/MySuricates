import { useEffect, useState } from 'react';
import { usePersistedState } from './usePersistedState';
import { useT } from '../i18n';

export type RunsViewMode = 'workflows' | 'chains';

export const RUNS_PAGE_SIZE = 50;
const SEARCH_DEBOUNCE_MS = 300;

/** Run statuses of the status filter, in this order. */
const FILTER_STATUSES = [
  'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'RUNNING', 'CANCELLED', 'PAUSED', 'PENDING',
  // After a restart of the instance — resumable from the run page
  'INTERRUPTED',
] as const;

/**
 * Tabs und Filter der Seite „Ausführungen“: gemerkte Filter je Tab (Status, Workflow, Projekt,
 * Suche), die entprellte Suche (setzt die Seite zurück), Seitenzahlen, der Tabwechsel (leert die
 * Filter des verlassenen Tabs) und die Abfrageparameter für `useAllRuns`/`useAllChainRuns`.
 * Ist das Modul `chains` aus, gilt immer der Workflow-Tab, auch wenn der Ketten-Tab gemerkt ist.
 */
export function useRunsFilters(chainsEnabled: boolean) {
  const t = useT();
  const [persistedViewMode, setViewMode] = usePersistedState<RunsViewMode>('runs-view-mode', 'workflows');
  // A remembered chains tab must not survive the chains module being switched off
  const viewMode: RunsViewMode = chainsEnabled ? persistedViewMode : 'workflows';

  // Workflow filters
  const [statusFilter, setStatusFilter] = usePersistedState('runs-status-filter', '');
  const [workflowFilter, setWorkflowFilter] = usePersistedState('runs-workflow-filter', '');
  const [projectFilter, setProjectFilter] = usePersistedState('runs-project-filter', '');
  const [search, setSearch] = usePersistedState('runs-search', '');
  const [debouncedSearch, setDebouncedSearch] = useState(search);
  const [page, setPage] = useState(0);

  // Chain filters
  const [chainStatusFilter, setChainStatusFilter] = usePersistedState('runs-chain-status-filter', '');
  const [chainSearch, setChainSearch] = usePersistedState('runs-chain-search', '');
  const [debouncedChainSearch, setDebouncedChainSearch] = useState(chainSearch);
  const [chainPage, setChainPage] = useState(0);

  useEffect(() => {
    const id = setTimeout(() => { setDebouncedSearch(search); setPage(0); }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(id);
  }, [search]);

  useEffect(() => {
    const id = setTimeout(() => { setDebouncedChainSearch(chainSearch); setChainPage(0); }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(id);
  }, [chainSearch]);

  const changeTab = (mode: RunsViewMode) => {
    setViewMode(mode);
    if (mode === 'workflows') {
      setChainPage(0);
      setChainStatusFilter('');
      setChainSearch('');
    } else {
      setPage(0);
      setStatusFilter('');
      setWorkflowFilter('');
      setProjectFilter('');
      setSearch('');
    }
  };

  const statusOptions = [
    { value: '', label: t.runs.allStatuses },
    ...FILTER_STATUSES.map((status) => ({ value: status, label: t.runs.statuses[status] })),
  ];

  return {
    viewMode,
    changeTab,
    statusOptions,
    workflow: {
      status: statusFilter, setStatus: setStatusFilter,
      workflowId: workflowFilter, setWorkflowId: setWorkflowFilter,
      projectId: projectFilter, setProjectId: setProjectFilter,
      search, setSearch,
      page, setPage,
    },
    chain: {
      status: chainStatusFilter, setStatus: setChainStatusFilter,
      search: chainSearch, setSearch: setChainSearch,
      page: chainPage, setPage: setChainPage,
    },
    workflowQuery: {
      status: statusFilter || undefined,
      workflowId: workflowFilter || undefined,
      projectId: projectFilter || undefined,
      search: debouncedSearch || undefined,
      page,
      size: RUNS_PAGE_SIZE,
    },
    chainQuery: {
      status: chainStatusFilter || undefined,
      search: debouncedChainSearch || undefined,
      page: chainPage,
      size: RUNS_PAGE_SIZE,
    },
  };
}

export type RunsFilters = ReturnType<typeof useRunsFilters>;
