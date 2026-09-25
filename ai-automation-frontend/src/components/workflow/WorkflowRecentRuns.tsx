import { Clock, StickyNote } from 'lucide-react';
import { Card, CardHeader, Spinner } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import { GateStatusBadge } from '../shared/GateStatusBadge';
import { Pagination } from '../shared/Pagination';
import { useWorkflowRuns } from '../../hooks/useWorkflows';
import { useI18nStore, useT } from '../../i18n';
import { formatDateTime, formatDuration } from '../../utils/formatTime';

/** Runs per page. The detail sidebar asks for the same page for its memory panel (one request). */
export const RECENT_RUNS_PAGE_SIZE = 5;

interface WorkflowRecentRunsProps {
  workflowId: string;
  /**
   * `list`: the newest runs under a heading, with start time and duration (panel of the workflow list).
   * `card`: a card with the total, gate status, notes and paging (sidebar of the detail page).
   */
  variant: 'list' | 'card';
  onOpenRun: (runId: string) => void;
  /** Page shown by the card (0-based); the list always shows the newest runs. */
  page?: number;
  onPageChange?: (update: (page: number) => number) => void;
}

export function WorkflowRecentRuns({ workflowId, variant, onOpenRun, page = 0, onPageChange }: WorkflowRecentRunsProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: runs, isLoading } = useWorkflowRuns(workflowId, page, RECENT_RUNS_PAGE_SIZE);

  if (variant === 'card') {
    return (
      <Card padding={false}>
        <div className="p-5 pb-0">
          <CardHeader title={`${t.dashboard.recentRuns} (${runs?.totalElements ?? 0})`} />
        </div>
        {!runs?.content?.length ? (
          <div className="px-5 py-8 text-center text-sm text-text-tertiary">{t.workflows.noRuns}</div>
        ) : (
          <>
            <div className="divide-y divide-border">
              {runs.content.map((run) => (
                <div
                  key={run.id}
                  className="px-5 py-3 hover:bg-surface transition-colors cursor-pointer"
                  onClick={() => onOpenRun(run.id)}
                >
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <StatusBadge status={run.status} />
                      <GateStatusBadge run={run} />
                    </div>
                    <div className="flex items-center gap-2 text-xs text-text-tertiary">
                      <Clock className="w-3 h-3" />
                      {formatDuration(run.durationMs, locale)}
                    </div>
                  </div>
                  {(run.metadata?.notes as string) && (
                    <p className="text-xs text-text-tertiary mt-1 truncate">
                      <StickyNote className="w-3 h-3 text-accent inline mr-1 -mt-0.5" />
                      {(run.metadata.notes as string).slice(0, 80)}
                      {(run.metadata.notes as string).length > 80 ? '...' : ''}
                    </p>
                  )}
                </div>
              ))}
            </div>
            {runs.totalPages > 1 && onPageChange && (
              <div className="px-5 py-2">
                <Pagination
                  page={page}
                  totalPages={runs.totalPages}
                  totalElements={runs.totalElements}
                  label=""
                  setPage={onPageChange}
                />
              </div>
            )}
          </>
        )}
      </Card>
    );
  }

  const recentRuns = runs?.content ?? [];
  return (
    <div>
      <h3 className="text-sm font-semibold text-text mb-2 flex items-center gap-1.5">
        <Clock className="w-4 h-4 text-text-tertiary" />
        {t.dashboard.recentRuns}
      </h3>
      {isLoading ? (
        <div className="flex justify-center py-4">
          <Spinner />
        </div>
      ) : recentRuns.length === 0 ? (
        <p className="text-xs text-text-tertiary py-2">{t.workflows.noRuns}</p>
      ) : (
        <div className="space-y-1.5">
          {recentRuns.map((run) => (
            <button
              key={run.id}
              onClick={() => onOpenRun(run.id)}
              className="flex items-center gap-3 w-full px-3 py-2 rounded-lg hover:bg-surface-raised transition-colors text-sm text-left"
            >
              <StatusBadge status={run.status} />
              <span className="text-text-tertiary text-xs tabular-nums">
                {formatDateTime(run.startedAt, locale)}
              </span>
              {run.durationMs != null && (
                <span className="text-text-tertiary/60 text-xs tabular-nums">
                  {formatDuration(run.durationMs, locale)}
                </span>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
