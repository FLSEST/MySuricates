import { useMemo, type ReactNode } from 'react';
import { Link2 } from 'lucide-react';
import { StatusBadge } from '../shared/StatusBadge';
import { SealStateIcon } from '../workflow/SealStateIcon';
import { useRunDeletionAllowed } from '../../hooks/useSystemCapabilities';
import { useI18nStore, useT } from '../../i18n';
import { formatDuration, formatDateTime } from '../../utils/formatTime';
import type { ChainRun, WorkflowRunListItem } from '../../types';
import { DeleteRunButton } from './DeleteRunButton';
import { RunResultText } from './RunResultText';
import { ChainProgress } from './ChainProgress';

export interface RunColumn<T> {
  key: string;
  header: string;
  render: (r: T) => ReactNode;
  width?: string;
}

/** Columns of the workflow runs table; the delete column only when the instance allows deleting runs. */
export function useWorkflowRunColumns(): RunColumn<WorkflowRunListItem>[] {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const runDeletionAllowed = useRunDeletionAllowed();

  return useMemo(() => [
    {
      key: 'status',
      header: t.common.status,
      render: (r: WorkflowRunListItem) => <StatusBadge status={r.status} />,
      width: '120px',
    },
    {
      key: 'workflow',
      header: t.common.workflow,
      render: (r: WorkflowRunListItem) => (
        <div className="min-w-0">
          <span className="font-medium text-text flex items-center gap-1.5 min-w-0">
            <span className="truncate">{r.workflowName}</span>
            <SealStateIcon sealed={r.sealed} unrecorded={r.sealed == null} />
          </span>
          {r.projectName && (
            <span className="text-xs text-accent/70 block truncate">{r.projectName}</span>
          )}
        </div>
      ),
    },
    {
      key: 'steps',
      header: t.workflows.execution.steps,
      render: (r: WorkflowRunListItem) => (
        <span className="text-text-secondary">{r.stepCount}</span>
      ),
      width: '80px',
    },
    {
      key: 'result',
      header: t.runs.result,
      render: (r: WorkflowRunListItem) => <RunResultText run={r} />,
    },
    {
      key: 'duration',
      header: t.runs.duration,
      render: (r: WorkflowRunListItem) => (
        <span className="text-text-secondary font-mono text-xs">
          {formatDuration(r.durationMs)}
        </span>
      ),
      width: '100px',
    },
    {
      key: 'date',
      header: t.runs.date,
      render: (r: WorkflowRunListItem) => (
        <span className="text-text-secondary text-xs">
          {r.startedAt ? formatDateTime(r.startedAt, locale) : '-'}
        </span>
      ),
      width: '120px',
    },
    // automation.executions.allow-delete=false: no delete column at all
    ...(runDeletionAllowed ? [{
      key: 'actions',
      header: '',
      render: (r: WorkflowRunListItem) => <DeleteRunButton run={r} />,
      width: '48px',
    }] : []),
  ], [t, locale, runDeletionAllowed]);
}

/** Columns of the chain runs table. */
export function useChainRunColumns(): RunColumn<ChainRun>[] {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);

  return useMemo(() => [
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
          {r.startedAt ? formatDateTime(r.startedAt, locale) : '-'}
        </span>
      ),
      width: '120px',
    },
  ], [t, locale]);
}
