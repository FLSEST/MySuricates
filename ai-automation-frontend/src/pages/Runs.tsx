import { useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { History } from 'lucide-react';
import { Card, Select, Spinner, Table } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { useAllRuns, useWorkflows } from '../hooks/useWorkflows';
import { useProjects } from '../hooks/useProjects';
import { useT } from '../i18n';
import { formatDuration, formatDateTime } from '../utils/formatTime';
import type { WorkflowRun } from '../types';

export function Runs() {
  const t = useT();
  const navigate = useNavigate();

  const [statusFilter, setStatusFilter] = useState('');
  const [workflowFilter, setWorkflowFilter] = useState('');
  const [projectFilter, setProjectFilter] = useState('');
  const [search, setSearch] = useState('');

  const [page, setPage] = useState(0);
  const pageSize = 50;

  const { data: runsPage, isLoading, error, refetch } = useAllRuns({
    status: statusFilter || undefined,
    workflowId: workflowFilter || undefined,
    projectId: projectFilter || undefined,
    page,
    size: pageSize,
  });
  const { data: workflows } = useWorkflows();
  const { data: projects } = useProjects();

  const runs = runsPage?.content;

  const filteredRuns = useMemo(() => {
    if (!runs) return [];
    if (!search.trim()) return runs;
    const q = search.toLowerCase();
    return runs.filter((r) => r.workflowName?.toLowerCase().includes(q));
  }, [runs, search]);

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

  const statusOptions = [
    { value: '', label: t.runs.allStatuses },
    { value: 'COMPLETED', label: 'Completed' },
    { value: 'FAILED', label: 'Failed' },
    { value: 'RUNNING', label: 'Running' },
    { value: 'CANCELLED', label: 'Cancelled' },
    { value: 'PAUSED', label: 'Paused' },
    { value: 'PENDING', label: 'Pending' },
  ];

  const columns = [
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

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-text">{t.runs.title}</h1>
        <p className="text-sm text-text-secondary mt-1">{t.runs.subtitle}</p>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap items-end gap-3">
        <div className="w-40">
          <Select
            label={t.runs.filterStatus}
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            options={statusOptions}
          />
        </div>
        <div className="w-48">
          <Select
            label={t.runs.filterWorkflow}
            value={workflowFilter}
            onChange={(e) => setWorkflowFilter(e.target.value)}
            options={[
              { value: '', label: t.runs.allWorkflows },
              ...(workflows?.map((w) => ({ value: w.id, label: w.name })) ?? []),
            ]}
          />
        </div>
        <div className="w-48">
          <Select
            label={t.runs.filterProject}
            value={projectFilter}
            onChange={(e) => setProjectFilter(e.target.value)}
            options={[
              { value: '', label: t.runs.allProjects },
              ...(projects?.map((p) => ({ value: p.id, label: p.name })) ?? []),
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
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t.common.search}
            className="w-full px-3 py-2 rounded-lg border border-border bg-surface text-text text-sm
                       focus:outline-none focus:ring-2 focus:ring-accent/50"
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
            keyExtractor={(r) => r.id}
            onRowClick={(r) => navigate(`/workflows/${r.workflowId}/run/${r.id}`)}
          />
        </Card>
      )}

      {/* Pagination */}
      {runsPage && runsPage.totalPages > 1 && (
        <div className="flex items-center justify-between mt-4">
          <span className="text-sm text-text-secondary">
            {runsPage.totalElements} {t.runs.title}
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
              {page + 1} / {runsPage.totalPages}
            </span>
            <button
              onClick={() => setPage((p) => Math.min(runsPage.totalPages - 1, p + 1))}
              disabled={page >= runsPage.totalPages - 1}
              className="px-3 py-1.5 text-sm rounded-lg border border-border bg-surface text-text disabled:opacity-40"
            >
              &rarr;
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
