import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, ArrowDown, ExternalLink, XCircle, CheckCircle2, AlertCircle, Clock, Loader2 } from 'lucide-react';
import { Card, Button, Badge, Spinner } from '../components/ui';
import { useChainRun, useCancelChainRun } from '../hooks/useChains';
import { useT } from '../i18n';
import { formatDuration } from '../utils/formatTime';
import type { ChainRunEntry } from '../types';

function EntryStatusIcon({ status }: { status: string }) {
  switch (status) {
    case 'COMPLETED':
      return <CheckCircle2 className="w-5 h-5 text-success" />;
    case 'FAILED':
      return <AlertCircle className="w-5 h-5 text-danger" />;
    case 'RUNNING':
      return <Loader2 className="w-5 h-5 text-accent animate-spin" />;
    case 'CANCELLED':
      return <XCircle className="w-5 h-5 text-warning" />;
    default:
      return <Clock className="w-5 h-5 text-text-tertiary" />;
  }
}

function StatusBadge({ status }: { status: string }) {
  const variant = status === 'COMPLETED' ? 'success'
    : status === 'FAILED' ? 'danger'
    : status === 'RUNNING' ? 'accent'
    : status === 'CANCELLED' ? 'warning'
    : 'default';
  return <Badge variant={variant} dot>{status}</Badge>;
}

export function ChainExecution() {
  const { id: chainId, runId } = useParams<{ id: string; runId: string }>();
  const navigate = useNavigate();
  const t = useT();
  const { data: run, isLoading } = useChainRun(chainId ?? '', runId ?? '');
  const cancelRun = useCancelChainRun();

  if (isLoading || !run) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }

  const isRunning = run.status === 'RUNNING' || run.status === 'PENDING';

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="sm" icon={<ArrowLeft className="w-4 h-4" />} onClick={() => navigate('/chains')} />
          <div>
            <h1 className="text-2xl font-bold text-text-primary">{t.chains.execution.title}</h1>
            {run.chainName && (
              <p className="text-text-secondary">{run.chainName}</p>
            )}
          </div>
        </div>
        <div className="flex items-center gap-3">
          <StatusBadge status={run.status} />
          {run.durationMs != null && (
            <span className="text-sm text-text-tertiary">{formatDuration(run.durationMs)}</span>
          )}
          {isRunning && (
            <Button
              variant="danger"
              size="sm"
              icon={<XCircle className="w-4 h-4" />}
              onClick={() => cancelRun.mutate(run.id)}
              disabled={cancelRun.isPending}
            >
              {t.chains.execution.cancel}
            </Button>
          )}
        </div>
      </div>

      {/* Error */}
      {run.error && (
        <Card className="border-danger/30">
          <div className="p-4 flex items-start gap-3">
            <AlertCircle className="w-5 h-5 text-danger shrink-0 mt-0.5" />
            <p className="text-sm text-danger">{run.error}</p>
          </div>
        </Card>
      )}

      {/* Pipeline Visualization */}
      <div className="space-y-2">
        {run.entryRuns.map((entry, i) => (
          <div key={i}>
            {i > 0 && (
              <div className="flex justify-center py-1">
                <ArrowDown className="w-4 h-4 text-text-tertiary" />
              </div>
            )}
            <EntryCard entry={entry} />
          </div>
        ))}

        {/* Pending entries (not yet started) */}
        {isRunning && run.entryRuns.length === 0 && (
          <Card className="p-6 text-center">
            <Spinner text={t.chains.execution.running} />
          </Card>
        )}
      </div>
    </div>
  );
}

function EntryCard({ entry }: { entry: ChainRunEntry }) {
  const t = useT();
  const navigate = useNavigate();

  const canNavigate = entry.workflowRunId && entry.workflowId;

  return (
    <Card className={`transition-all ${
      entry.status === 'RUNNING' ? 'ring-2 ring-accent/50' :
      entry.status === 'FAILED' ? 'border-danger/30' :
      ''
    }`}>
      <div className="p-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <EntryStatusIcon status={entry.status} />
            <div>
              <p className="font-medium text-text-primary">
                {entry.workflowName || entry.workflowId}
              </p>
              <div className="flex items-center gap-2 mt-0.5">
                <span className="text-xs text-text-tertiary">#{entry.entryIndex + 1}</span>
                {entry.durationMs != null && (
                  <span className="text-xs text-text-tertiary">{formatDuration(entry.durationMs)}</span>
                )}
              </div>
            </div>
          </div>

          {canNavigate && (
            <Button
              variant="ghost"
              size="sm"
              icon={<ExternalLink className="w-4 h-4" />}
              onClick={() => navigate(`/workflows/${entry.workflowId}/run/${entry.workflowRunId}`)}
            >
              {t.chains.execution.viewRun}
            </Button>
          )}
        </div>
      </div>
    </Card>
  );
}
