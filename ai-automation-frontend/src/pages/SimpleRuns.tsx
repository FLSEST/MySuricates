import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { RotateCcw } from 'lucide-react';
import { useT } from '../i18n';
import { useAllRuns, useExecuteWorkflow } from '../hooks/useWorkflows';
import { StatusBadge } from '../components/shared/StatusBadge';
import { Spinner, Button, Card } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { EmptyState } from '../components/shared/EmptyState';
import { formatDateTime, formatDuration } from '../utils/formatTime';
import type { WorkflowRun } from '../types';

export function SimpleRuns() {
  const t = useT();
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
    <div className="animate-fade-in max-w-3xl mx-auto">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-text">{t.simple.runs.title}</h1>
        <p className="text-sm text-text-secondary mt-1">{t.simple.runs.subtitle}</p>
      </div>

      {ordered.length === 0 ? (
        <EmptyState title={t.simple.runs.empty} description={t.simple.runs.emptyHint} />
      ) : (
        <div className="space-y-2">
          {ordered.map((run: WorkflowRun) => {
            const isFailed = run.status === 'FAILED';
            return (
              <Card key={run.id}>
                <div className="flex items-center justify-between gap-3">
                  <div
                    className="flex items-center gap-3 min-w-0 cursor-pointer"
                    onClick={() => navigate(`/workflows/${run.workflowId}/run/${run.id}`)}
                  >
                    <StatusBadge status={run.status} />
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-text truncate">{run.workflowName}</p>
                      <p className="text-xs text-text-tertiary truncate">
                        {run.startedAt ? formatDateTime(run.startedAt) : '-'} · {formatDuration(run.durationMs)}
                        {isFailed && run.error?.message ? ` · ${run.error.message}` : ''}
                      </p>
                    </div>
                  </div>
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
                </div>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
