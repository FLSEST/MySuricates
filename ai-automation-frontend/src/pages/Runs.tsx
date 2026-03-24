import { useState, useMemo } from 'react';
import { usePersistedState } from '../hooks/usePersistedState';
import { useNavigate } from 'react-router-dom';
import { History, Link2 } from 'lucide-react';
import { Card, Select, Spinner, Table } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { useAllRuns, useWorkflows } from '../hooks/useWorkflows';
import { useProjects } from '../hooks/useProjects';
import { useAllChainRuns } from '../hooks/useChains';
import { useT } from '../i18n';
import { formatDuration, formatDateTime } from '../utils/formatTime';
import type { WorkflowRun, ChainRun } from '../types';

type ViewMode = 'workflows' | 'chains';

export function Runs() {
  const t = useT();
  const navigate = useNavigate();

  const [viewMode, setViewMode] = usePersistedState<ViewMode>('runs-view-mode', 'workflows');

  // Workflow filters
  const [statusFilter, setStatusFilter] = usePersistedState('runs-status-filter', '');
  const [workflowFilter, setWorkflowFilter] = usePersistedState('runs-workflow-filter', '');
  const [projectFilter, setProjectFilter] = usePersistedState('runs-project-filter', '');
  const [search, setSearch] = usePersistedState('runs-search', '');
  const [page, setPage] = useState(0);
  const pageSize = 50;

  // Chain filters
  const [chainStatusFilter, setChainStatusFilter] = usePersistedState('runs-chain-status-filter', '');
  const [chainSearch, setChainSearch] = usePersistedState('runs-chain-search', '');
  const [chainPage, setChainPage] = useState(0);

  const { data: runsPage, isLoading, error, refetch } = useAllRuns({
    status: statusFilter || undefined,
    workflowId: workflowFilter || undefined,
    projectId: projectFilter || undefined,
    page,
    size: pageSize,
  });
  const { data: workflows } = useWorkflows();
  const { data: projects } = useProjects();

  const {
    data: chainRunsPage,
    isLoading: chainLoading,
    error: chainError,
    refetch: chainRefetch,
  } = useAllChainRuns({
    status: chainStatusFilter || undefined,
    page: chainPage,
    size: pageSize,
  });

  const runs = runsPage?.content;
  const chainRuns = chainRunsPage?.content;

  const filteredRuns = useMemo(() => {
    if (!runs) return [];
    if (!search.trim()) return runs;
    const q = search.toLowerCase();
    return runs.filter((r) => r.workflowName?.toLowerCase().includes(q));
  }, [runs, search]);

  const filteredChainRuns = useMemo(() => {
    if (!chainRuns) return [];
    if (!chainSearch.trim()) return chainRuns;
    const q = chainSearch.toLowerCase();
    return chainRuns.filter((r) => r.chainName?.toLowerCase().includes(q));
  }, [chainRuns, chainSearch]);

  const handleTabChange = (mode: ViewMode) => {
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
    { value: 'COMPLETED', label: 'Completed' },
    { value: 'COMPLETED_WITH_ERRORS', label: 'Partial' },
    { value: 'FAILED', label: 'Failed' },
    { value: 'RUNNING', label: 'Running' },
    { value: 'CANCELLED', label: 'Cancelled' },
    { value: 'PAUSED', label: 'Paused' },
    { value: 'PENDING', label: 'Pending' },
  ];

  const workflowColumns = [
    {
      key: 'status',
      header: t.common.status,
      render: (r: WorkflowRun) => <StatusBadge status={r.status} />,
      width: '120px',
    },
    {
      key: 'workflow',
      header: t.common.workflow,
      render: (r: WorkflowRun) => (
        <div className="min-w-0">
          <span className="font-medium text-text block truncate">{r.workflowName}</span>
          {r.projectName && (
            <span className="text-xs text-accent/70 block truncate">{r.projectName}</span>
          )}
        </div>
      ),
    },
    {
      key: 'steps',
      header: t.workflows.execution.steps,
      render: (r: WorkflowRun) => (
        <span className="text-text-secondary">{r.steps?.length ?? 0}</span>
      ),
      width: '80px',
    },
    {
      key: 'duration',
      header: t.runs.duration,
      render: (r: WorkflowRun) => (
        <span className="text-text-secondary font-mono text-xs">
          {formatDuration(r.durationMs)}
        </span>
      ),
      width: '100px',
    },
    {
      key: 'date',
      header: t.runs.date,
      render: (r: WorkflowRun) => (
        <span className="text-text-secondary text-xs">
          {r.startedAt ? formatDateTime(r.startedAt) : '-'}
        </span>
      ),
      width: '120px',
    },
  ];

  const chainColumns = [
    {
      key: 'status',
      header: t.common.status,
      render: (r: ChainRun) => <StatusBadge status={r.status} />,
      width: '120px',
    },
    {
      key: 'chain',
      header: t.nav.chains,
      render: (r: ChainRun) => (
        <div className="flex items-center gap-1.5 min-w-0">
          <Link2 className="w-3.5 h-3.5 text-accent/70 shrink-0" />
          <span className="font-medium text-text truncate">{r.chainName ?? '-'}</span>
        </div>
      ),
    },
    {
      key: 'progress',
      header: t.runs.progress,
      render: (r: ChainRun) => <ChainProgress entryRuns={r.entryRuns} />,
      width: '120px',
    },
    {
      key: 'duration',
      header: t.runs.duration,
      render: (r: ChainRun) => (
        <span className="text-text-secondary font-mono text-xs">
          {formatDuration(r.durationMs)}
        </span>
      ),
      width: '100px',
    },
    {
      key: 'date',
      header: t.runs.date,
      render: (r: ChainRun) => (
        <span className="text-text-secondary text-xs">
          {r.startedAt ? formatDateTime(r.startedAt) : '-'}
        </span>
      ),
      width: '120px',
    },
  ];

  const tabClass = (active: boolean) =>
    `px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
      active
        ? 'border-accent text-accent'
        : 'border-transparent text-text-secondary hover:text-text hover:border-border'
    }`;

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-text">{t.runs.title}</h1>
        <p className="text-sm text-text-secondary mt-1">{t.runs.subtitle}</p>
      </div>

      {/* Tabs */}
      <div className="flex border-b border-border">
        <button className={tabClass(viewMode === 'workflows')} onClick={() => handleTabChange('workflows')}>
          {t.runs.tabWorkflows}
        </button>
        <button className={tabClass(viewMode === 'chains')} onClick={() => handleTabChange('chains')}>
          {t.runs.tabChains}
        </button>
      </div>

      {viewMode === 'workflows' ? (
        <WorkflowsTab
          t={t}
          navigate={navigate}
          statusFilter={statusFilter}
          setStatusFilter={setStatusFilter}
          workflowFilter={workflowFilter}
          setWorkflowFilter={setWorkflowFilter}
          projectFilter={projectFilter}
          setProjectFilter={setProjectFilter}
          search={search}
          setSearch={setSearch}
          statusOptions={statusOptions}
          workflows={workflows}
          projects={projects}
          filteredRuns={filteredRuns}
          columns={workflowColumns}
          isLoading={isLoading}
          error={error}
          refetch={refetch}
          runsPage={runsPage}
          page={page}
          setPage={setPage}
        />
      ) : (
        <ChainsTab
          t={t}
          navigate={navigate}
          chainStatusFilter={chainStatusFilter}
          setChainStatusFilter={setChainStatusFilter}
          chainSearch={chainSearch}
          setChainSearch={setChainSearch}
          statusOptions={statusOptions}
          filteredChainRuns={filteredChainRuns}
          columns={chainColumns}
          isLoading={chainLoading}
          error={chainError}
          refetch={chainRefetch}
          chainRunsPage={chainRunsPage}
          chainPage={chainPage}
          setChainPage={setChainPage}
        />
      )}
    </div>
  );
}

function ChainProgress({ entryRuns }: { entryRuns: ChainRun['entryRuns'] }) {
  if (!entryRuns?.length) return <span className="text-text-secondary">-</span>;

  const total = entryRuns.length;
  const completed = entryRuns.filter((e) => e.status === 'COMPLETED').length;
  const failed = entryRuns.filter((e) => e.status === 'FAILED').length;

  const color = failed > 0
    ? 'text-red-500'
    : completed === total
      ? 'text-green-500'
      : 'text-text-secondary';

  return (
    <span className={`font-mono text-xs font-medium ${color}`}>
      {completed}/{total}
      {failed > 0 && <span className="text-red-500 ml-1">({failed} failed)</span>}
    </span>
  );
}

function Pagination({
  page,
  totalPages,
  totalElements,
  label,
  setPage,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  label: string;
  setPage: (fn: (p: number) => number) => void;
}) {
  if (totalPages <= 1) return null;
  return (
    <div className="flex items-center justify-between mt-4">
      <span className="text-sm text-text-secondary">
        {totalElements} {label}
      </span>
      <div className="flex gap-2">
        <button
          onClick={() => setPage((p) => Math.max(0, p - 1))}
          disabled={page === 0}
          className="px-3 py-1.5 text-sm rounded-lg border border-border bg-surface text-text disabled:opacity-40"
        >
          &larr;
        </button>
        <span className="px-3 py-1.5 text-sm text-text-secondary">
          {page + 1} / {totalPages}
        </span>
        <button
          onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
          disabled={page >= totalPages - 1}
          className="px-3 py-1.5 text-sm rounded-lg border border-border bg-surface text-text disabled:opacity-40"
        >
          &rarr;
        </button>
      </div>
    </div>
  );
}

/* eslint-disable @typescript-eslint/no-explicit-any */
function WorkflowsTab({
  t,
  navigate,
  statusFilter,
  setStatusFilter,
  workflowFilter,
  setWorkflowFilter,
  projectFilter,
  setProjectFilter,
  search,
  setSearch,
  statusOptions,
  workflows,
  projects,
  filteredRuns,
  columns,
  isLoading,
  error,
  refetch,
  runsPage,
  page,
  setPage,
}: any) {
  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }
  if (error) {
    return <ErrorState message={(error as Error).message} onRetry={() => refetch()} />;
  }

  return (
    <>
      {/* Filters */}
      <div className="flex flex-wrap items-end gap-3">
        <div className="w-40">
          <Select
            label={t.runs.filterStatus}
            value={statusFilter}
            onChange={(e: any) => setStatusFilter(e.target.value)}
            options={statusOptions}
          />
        </div>
        <div className="w-48">
          <Select
            label={t.runs.filterWorkflow}
            value={workflowFilter}
            onChange={(e: any) => setWorkflowFilter(e.target.value)}
            options={[
              { value: '', label: t.runs.allWorkflows },
              ...(workflows?.map((w: any) => ({ value: w.id, label: w.name })) ?? []),
            ]}
          />
        </div>
        <div className="w-48">
          <Select
            label={t.runs.filterProject}
            value={projectFilter}
            onChange={(e: any) => setProjectFilter(e.target.value)}
            options={[
              { value: '', label: t.runs.allProjects },
              ...(projects?.map((p: any) => ({ value: p.id, label: p.name })) ?? []),
            ]}
          />
        </div>
        <div className="flex-1 min-w-[200px]">
          <label className="block text-xs font-medium text-text-secondary mb-1">
            {t.common.search}
          </label>
          <input
            type="text"
            value={search}
            onChange={(e: any) => setSearch(e.target.value)}
            placeholder={t.common.search}
            className="w-full px-3 py-2 rounded-lg border border-border bg-surface text-text text-sm
                       focus:outline-hidden focus:ring-2 focus:ring-accent/50"
          />
        </div>
      </div>

      {/* Table */}
      {!filteredRuns.length ? (
        <EmptyState
          icon={<History className="w-8 h-8 text-text-tertiary" />}
          title={t.runs.title}
          description={t.runs.noRuns}
        />
      ) : (
        <Card padding={false}>
          <Table
            columns={columns}
            data={filteredRuns}
            keyExtractor={(r: any) => r.id}
            onRowClick={(r: any) => navigate(`/workflows/${r.workflowId}/run/${r.id}`)}
          />
        </Card>
      )}

      <Pagination
        page={page}
        totalPages={runsPage?.totalPages ?? 0}
        totalElements={runsPage?.totalElements ?? 0}
        label={t.runs.title}
        setPage={setPage}
      />
    </>
  );
}

function ChainsTab({
  t,
  navigate,
  chainStatusFilter,
  setChainStatusFilter,
  chainSearch,
  setChainSearch,
  statusOptions,
  filteredChainRuns,
  columns,
  isLoading,
  error,
  refetch,
  chainRunsPage,
  chainPage,
  setChainPage,
}: any) {
  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }
  if (error) {
    return <ErrorState message={(error as Error).message} onRetry={() => refetch()} />;
  }

  return (
    <>
      {/* Filters */}
      <div className="flex flex-wrap items-end gap-3">
        <div className="w-40">
          <Select
            label={t.runs.filterStatus}
            value={chainStatusFilter}
            onChange={(e: any) => setChainStatusFilter(e.target.value)}
            options={statusOptions}
          />
        </div>
        <div className="flex-1 min-w-[200px]">
          <label className="block text-xs font-medium text-text-secondary mb-1">
            {t.common.search}
          </label>
          <input
            type="text"
            value={chainSearch}
            onChange={(e: any) => setChainSearch(e.target.value)}
            placeholder={t.common.search}
            className="w-full px-3 py-2 rounded-lg border border-border bg-surface text-text text-sm
                       focus:outline-hidden focus:ring-2 focus:ring-accent/50"
          />
        </div>
      </div>

      {/* Table */}
      {!filteredChainRuns.length ? (
        <EmptyState
          icon={<Link2 className="w-8 h-8 text-text-tertiary" />}
          title={t.nav.chains}
          description={t.runs.noChainRuns}
        />
      ) : (
        <Card padding={false}>
          <Table
            columns={columns}
            data={filteredChainRuns}
            keyExtractor={(r: any) => r.id}
            onRowClick={(r: any) => navigate(`/chains/${r.chainId}/run/${r.id}`)}
          />
        </Card>
      )}

      <Pagination
        page={chainPage}
        totalPages={chainRunsPage?.totalPages ?? 0}
        totalElements={chainRunsPage?.totalElements ?? 0}
        label={t.nav.chains}
        setPage={setChainPage}
      />
    </>
  );
}
/* eslint-enable @typescript-eslint/no-explicit-any */
