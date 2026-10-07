import { History } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import type { UseQueryResult } from '@tanstack/react-query';
import { Card, Input, Select, Spinner, Table } from '../ui';
import { Pagination } from '../shared/Pagination';
import { EmptyState } from '../shared/EmptyState';
import { ErrorState } from '../shared/ErrorState';
import { useT } from '../../i18n';
import type { RunsFilters } from '../../hooks/useRunsFilters';
import type { PageResponse, Project, Workflow, WorkflowRunListItem } from '../../types';
import { useWorkflowRunColumns } from './runColumns';

interface WorkflowsTabProps {
  filters: RunsFilters['workflow'];
  statusOptions: RunsFilters['statusOptions'];
  workflows: Workflow[] | undefined;
  projects: Project[] | undefined;
  runs: UseQueryResult<PageResponse<WorkflowRunListItem>>;
}

/** Workflow runs of the runs page: filters, table (row opens the run) and paging. */
export function WorkflowsTab({ filters, statusOptions, workflows, projects, runs }: WorkflowsTabProps) {
  const t = useT();
  const navigate = useNavigate();
  const columns = useWorkflowRunColumns();
  const { data: runsPage, isLoading, error, refetch } = runs;
  const filteredRuns = runsPage?.content ?? [];

  return (
    <>
      {/* Filters */}
      <div className="flex flex-wrap items-end gap-3">
        <div className="w-40">
          <Select
            label={t.runs.filterStatus}
            value={filters.status}
            onChange={(e) => filters.setStatus(e.target.value)}
            options={statusOptions}
          />
        </div>
        <div className="w-48">
          <Select
            label={t.runs.filterWorkflow}
            value={filters.workflowId}
            onChange={(e) => filters.setWorkflowId(e.target.value)}
            options={[
              { value: '', label: t.runs.allWorkflows },
              ...(workflows?.map((w) => ({ value: w.id, label: w.name })) ?? []),
            ]}
          />
        </div>
        <div className="w-48">
          <Select
            label={t.runs.filterProject}
            value={filters.projectId}
            onChange={(e) => filters.setProjectId(e.target.value)}
            options={[
              { value: '', label: t.runs.allProjects },
              ...(projects?.map((p) => ({ value: p.id, label: p.name })) ?? []),
            ]}
          />
        </div>
        <div className="flex-1 min-w-[200px]">
          <Input
            type="text"
            label={t.common.search}
            value={filters.search}
            onChange={(e) => filters.setSearch(e.target.value)}
            placeholder={t.common.search}
            className="text-sm"
          />
        </div>
      </div>

      {/* Table */}
      {isLoading ? (
        <div className="flex items-center justify-center h-64">
          <Spinner text={t.common.loading} />
        </div>
      ) : error ? (
        <ErrorState message={error.message} onRetry={() => refetch()} />
      ) : !filteredRuns.length ? (
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

      <Pagination
        page={filters.page}
        totalPages={runsPage?.totalPages ?? 0}
        totalElements={runsPage?.totalElements ?? 0}
        label={t.runs.title}
        setPage={filters.setPage}
      />
    </>
  );
}
