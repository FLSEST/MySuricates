import { Link2 } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import type { UseQueryResult } from '@tanstack/react-query';
import { Card, Input, Select, Spinner, Table } from '../ui';
import { Pagination } from '../shared/Pagination';
import { EmptyState } from '../shared/EmptyState';
import { ErrorState } from '../shared/ErrorState';
import { useT } from '../../i18n';
import type { RunsFilters } from '../../hooks/useRunsFilters';
import type { ChainRun, PageResponse } from '../../types';
import { useChainRunColumns } from './runColumns';

interface ChainsTabProps {
  filters: RunsFilters['chain'];
  statusOptions: RunsFilters['statusOptions'];
  chainRuns: UseQueryResult<PageResponse<ChainRun>>;
}

/** Chain runs of the runs page: filters, table (row opens the chain run) and paging. */
export function ChainsTab({ filters, statusOptions, chainRuns }: ChainsTabProps) {
  const t = useT();
  const navigate = useNavigate();
  const columns = useChainRunColumns();
  const { data: chainRunsPage, isLoading, error, refetch } = chainRuns;
  const filteredChainRuns = chainRunsPage?.content ?? [];

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
      ) : !filteredChainRuns.length ? (
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
            keyExtractor={(r) => r.id}
            onRowClick={(r) => navigate(`/chains/${r.chainId}/run/${r.id}`)}
          />
        </Card>
      )}

      <Pagination
        page={filters.page}
        totalPages={chainRunsPage?.totalPages ?? 0}
        totalElements={chainRunsPage?.totalElements ?? 0}
        label={t.nav.chains}
        setPage={filters.setPage}
      />
    </>
  );
}
