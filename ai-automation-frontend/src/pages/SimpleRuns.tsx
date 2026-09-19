import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { RotateCcw } from 'lucide-react';
import { useT, useI18nStore } from '../i18n';
import { useAllRuns, useExecuteWorkflow } from '../hooks/useWorkflows';
import { Spinner, Button } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { EmptyState } from '../components/shared/EmptyState';
import { SimplePage, SimplePageHeader, SimpleRow, StatusDot } from '../components/simple/kit';
import { AREA_COLORS } from '../utils/homeTiles';
import { formatDateTime, formatDuration } from '../utils/formatTime';
import type { WorkflowRunListItem } from '../types';

export function SimpleRuns() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const navigate = useNavigate();
  const { data: runsPage, isLoading, error, refetch } = useAllRuns({ size: 50 });
  const executeWorkflow = useExecuteWorkflow();

  const ordered = useMemo(() => {
    const runs = [...(runsPage?.content ?? [])];
    // Fehler zuerst, dann nach Startzeit absteigend.
    return runs.sort((a, b) => {
      const af = a.status === 'FAILED' ? 0 : 1;
      const bf = b.status === 'FAILED' ? 0 : 1;
      if (af !== bf) return af - bf;
      return (b.startedAt ?? '').localeCompare(a.startedAt ?? '');
    });
  }, [runsPage]);

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }
  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <SimplePage>
      <SimplePageHeader title={t.simple.runs.title} subtitle={t.simple.runs.subtitle} />

      {ordered.length === 0 ? (
        <EmptyState title={t.simple.runs.empty} description={t.simple.runs.emptyHint} />
      ) : (
        <div className="space-y-2">
          {ordered.map((run: WorkflowRunListItem) => {
            const isFailed = run.status === 'FAILED';
            return (
              <SimpleRow key={run.id} color={AREA_COLORS.results}>
                <button
                  className="flex items-center gap-3 min-w-0 text-left"
                  onClick={() => navigate(`/workflows/${run.workflowId}/run/${run.id}`)}
                >
                  <StatusDot status={run.status} />
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-text truncate">{run.workflowName}</p>
                    <p className="text-xs text-text-tertiary truncate">
                      {run.startedAt ? formatDateTime(run.startedAt, locale) : '-'} · {formatDuration(run.durationMs)}
                      {isFailed && run.error?.message ? ` · ${run.error.message}` : ''}
                    </p>
                  </div>
                </button>
                {isFailed && (
                  <Button
                    size="sm"
                    variant="secondary"
                    icon={<RotateCcw className="w-3.5 h-3.5" />}
                    loading={executeWorkflow.isPending}
                    onClick={() =>
                      executeWorkflow.mutate(
                        { id: run.workflowId },
                        { onSuccess: (r) => navigate(`/workflows/${run.workflowId}/run/${r.id}`) },
                      )
                    }
                  >
                    {t.simple.runs.retry}
                  </Button>
                )}
              </SimpleRow>
            );
          })}
        </div>
      )}
    </SimplePage>
  );
}
