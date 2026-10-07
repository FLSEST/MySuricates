import { Link, useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, ArrowDown, ExternalLink, XCircle, CheckCircle2, AlertCircle, Clock, Loader2 } from 'lucide-react';
import { Card, Button, Spinner } from '../components/ui';
import { TriggerSourceChip } from '../components/workflow/TriggerSourceChip';
import { ChainStatusBadge } from '../components/chains/ChainStatusBadge';
import { useChainRun, useCancelChainRun } from '../hooks/useChains';
import { useI18nStore, useT } from '../i18n';
import { formatDuration } from '../utils/formatTime';
import { chainLink, scheduleTriggerLink } from '../utils/entityLinks';
import type { ChainRunEntry } from '../types';

function EntryStatusIcon({ status }: { status: string }) {
  switch (status) {
    case 'COMPLETED':
      return <CheckCircle2 className="w-5 h-5 text-success" />;
    case 'COMPLETED_WITH_ERRORS':
      return <CheckCircle2 className="w-5 h-5 text-warning" />;
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

export function ChainExecution() {
  const { id: chainId, runId } = useParams<{ id: string; runId: string }>();
  const navigate = useNavigate();
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
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
          <Button variant="ghost" size="sm" icon={<ArrowLeft className="w-4 h-4" />} onClick={() => navigate(chainLink(run.chainId))} title={t.common.back} />
          <div>
            <h1 className="text-2xl font-bold text-text">{t.chains.execution.title}</h1>
            {run.chainName && (
              <p>
                <Link to={chainLink(run.chainId)} className="text-text-secondary hover:text-accent hover:underline" data-testid="chain-run-chain">
                  {run.chainName}
                </Link>
              </p>
            )}
            {run.triggerSource && (
              <div className="mt-1 text-xs">
                <TriggerSourceChip source={run.triggerSource} to={scheduleTriggerLink(run.triggerSource, run.triggeredByScheduleId)} />
              </div>
            )}
          </div>
        </div>
        <div className="flex items-center gap-3">
          <ChainStatusBadge status={run.status} />
          {run.durationMs != null && (
            <span className="text-sm text-text-tertiary">{formatDuration(run.durationMs, locale)}</span>
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
  const locale = useI18nStore((s) => s.locale);
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
              <p className="font-medium text-text">
                {entry.workflowName || entry.workflowId}
              </p>
              {entry.projectName && (
                <p className="text-xs text-text-tertiary">{entry.projectName}</p>
              )}
              <div className="flex items-center gap-2 mt-0.5">
                <span className="text-xs text-text-tertiary">#{entry.entryIndex + 1}</span>
                {entry.durationMs != null && (
                  <span className="text-xs text-text-tertiary">{formatDuration(entry.durationMs, locale)}</span>
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
