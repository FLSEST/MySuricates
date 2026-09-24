import { Hint } from '../components/hints/Hint';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Bug, RotateCcw, Stethoscope, ArrowRight, Clock, CalendarClock } from 'lucide-react';
import { Button, ConfirmModal, Spinner } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { useBrokenWorkflows } from '../hooks/useWorkflowDebug';
import { useDeadLetters } from '../hooks/useDeadLetters';
import { useRetryExecution } from '../hooks/useWorkflows';
import { useAssistantStore } from '../stores/assistantStore';
import { useModuleEnabled } from '../hooks/useModules';
import { useT, useI18nStore } from '../i18n';
import { formatRelativeTime, formatDuration } from '../utils/formatTime';
import type { BrokenWorkflow } from '../types';

export function WorkflowDebug() {
  const t = useT();
  const { data, isLoading, error, refetch } = useBrokenWorkflows();
  // The DLQ page has no nav entry; this page is where failed runs are looked at (d75fbfef)
  const { data: openDeadLetters } = useDeadLetters('OPEN', 0, 1);
  const [restartTarget, setRestartTarget] = useState<BrokenWorkflow | null>(null);
  const retry = useRetryExecution();

  const broken = data ?? [];

  const handleRestart = () => {
    if (!restartTarget) return;
    retry.mutate(restartTarget.runId, { onSuccess: () => setRestartTarget(null) });
  };

  return (
    <div className="space-y-4 p-6">
      <header>
        <h1 className="text-2xl font-bold text-text flex items-center gap-2">
          <Bug className="w-6 h-6 text-danger" />
          {t.workflowDebug.title}
        </h1>
        <p className="text-sm text-text-secondary mt-1">{t.workflowDebug.subtitle}</p>
        <Link to="/dlq" className="mt-2 inline-flex items-center gap-1 text-sm text-accent hover:underline" data-testid="dlq-link">
          {t.workflowDebug.dlqLink}
          {!!openDeadLetters?.totalElements && (
            <span className="text-text-secondary">
              ({t.workflowDebug.dlqOpen.replace('{count}', String(openDeadLetters.totalElements))})
            </span>
          )}
          <ArrowRight className="w-3 h-3" />
        </Link>
      </header>

      <Hint id="debug-failing" />

      {isLoading ? (
        <div className="flex justify-center py-12"><Spinner /></div>
      ) : error ? (
        <ErrorState message={t.workflowDebug.loadError} onRetry={() => refetch()} />
      ) : broken.length === 0 ? (
        <EmptyState
          icon={<Bug className="w-10 h-10 text-success" />}
          title={t.workflowDebug.empty}
        />
      ) : (
        <>
          <p className="text-sm text-text-tertiary">
            {broken.length} {t.workflowDebug.failingCount}
          </p>
          <div className="space-y-2">
            {broken.map((wf) => (
              <BrokenRow key={wf.runId} wf={wf} onRestart={() => setRestartTarget(wf)} />
            ))}
          </div>
        </>
      )}

      <ConfirmModal
        open={!!restartTarget}
        onClose={() => setRestartTarget(null)}
        onConfirm={handleRestart}
        title={t.workflowDebug.restartConfirm}
        confirmLabel={t.workflowDebug.restart}
        isPending={retry.isPending}
        cancelVariant="ghost"
        size="md"
      >
        <p className="text-text-secondary">{t.workflowDebug.restartConfirmHint}</p>
      </ConfirmModal>
    </div>
  );
}

function BrokenRow({ wf, onRestart }: { wf: BrokenWorkflow; onRestart: () => void }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  // The diagnosis opens the assistant panel, which only exists with the assistant module
  const assistantModule = useModuleEnabled('assistant');
  const setPendingInput = useAssistantStore((s) => s.setPendingInput);
  const setAssistantOpen = useAssistantStore((s) => s.setOpen);
  const startNewSession = useAssistantStore((s) => s.startNewSession);
  const setShowHistory = useAssistantStore((s) => s.setShowHistory);
  const setPendingContext = useAssistantStore((s) => s.setPendingContext);

  const handleDiagnose = () => {
    const stepLabel = wf.failedStepIndex != null
      ? `${wf.failedStepIndex + 1}${wf.failedBlockType ? ` (${wf.failedBlockType})` : ''}`
      : '—';
    // Keep the END of the message — the actual error (HTTP status, exception) sits there.
    const errorText = wf.errorMessage
      ? (wf.errorMessage.length > 600 ? `…${wf.errorMessage.slice(-600)}` : wf.errorMessage)
      : t.workflowDebug.noError;
    const prompt = t.workflowDebug.diagnosePrompt
      .replace('{name}', () => wf.workflowName ?? '—')
      .replace('{status}', () => wf.status)
      .replace('{step}', () => stepLabel)
      .replace('{error}', () => errorText)
      .replace('{runId}', () => wf.runId);
    // Hand the assistant the specific run as STRUCTURED context (not just prompt text) so the
    // backend auto-loads its status/error/steps — and open it in its own fresh chat.
    setPendingContext({
      page: 'run',
      entityId: wf.runId,
      runId: wf.runId,
      workflowId: wf.workflowId ?? undefined,
      pathname: wf.workflowId ? `/workflows/${wf.workflowId}/run/${wf.runId}` : '/debug',
    });
    startNewSession();
    setShowHistory(false);
    setPendingInput(prompt);
    setAssistantOpen(true);
  };

  return (
    <div className="bg-surface-raised border border-border rounded-lg p-4">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            {wf.workflowId ? (
              <Link
                to={`/workflows/${wf.workflowId}`}
                className="font-medium text-text hover:text-accent truncate"
              >
                {wf.workflowName ?? wf.workflowId}
              </Link>
            ) : (
              <span className="font-medium text-text truncate">{wf.workflowName ?? '—'}</span>
            )}
            <StatusBadge status={wf.status} />
            {wf.projectName && <span className="text-xs text-text-tertiary">{wf.projectName}</span>}
            {wf.triggeredByScheduleId && (
              <span className="inline-flex items-center gap-1 text-xs text-text-tertiary">
                <CalendarClock className="w-3 h-3" />
                {t.workflowDebug.scheduled}
              </span>
            )}
          </div>

          <p className="text-sm text-danger mt-1.5 line-clamp-2 font-mono">
            {wf.errorMessage ?? t.workflowDebug.noError}
          </p>

          <div className="flex items-center gap-3 mt-2 text-xs text-text-tertiary flex-wrap">
            {wf.failedStepIndex != null && (
              <span>
                {t.workflowDebug.failedStep} {wf.failedStepIndex + 1}
                {wf.failedBlockType ? ` (${wf.failedBlockType})` : ''}
              </span>
            )}
            <span className="inline-flex items-center gap-1">
              <Clock className="w-3 h-3" />
              {t.workflowDebug.failedAt}: {formatRelativeTime(wf.completedAt ?? wf.startedAt, locale)}
            </span>
            {wf.durationMs != null && (
              <span>{t.workflowDebug.duration}: {formatDuration(wf.durationMs)}</span>
            )}
            {wf.workflowId && (
              <Link
                to={`/workflows/${wf.workflowId}/run/${wf.runId}`}
                className="text-accent hover:underline inline-flex items-center gap-0.5"
              >
                {t.workflowDebug.openRun} <ArrowRight className="w-3 h-3" />
              </Link>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2 shrink-0">
          {assistantModule && (
            <Button
              variant="secondary"
              size="sm"
              icon={<Stethoscope className="w-4 h-4" />}
              onClick={handleDiagnose}
              title={t.workflowDebug.diagnoseTitle}
            >
              {t.workflowDebug.diagnose}
            </Button>
          )}
          <Button
            variant="ghost"
            size="sm"
            icon={<RotateCcw className="w-4 h-4" />}
            onClick={onRestart}
            title={t.workflowDebug.restart}
          >
            {t.workflowDebug.restart}
          </Button>
        </div>
      </div>
    </div>
  );
}
