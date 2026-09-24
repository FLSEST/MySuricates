import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ArrowLeft, Pause, Play, XCircle, CheckCircle2, AlertCircle,
} from 'lucide-react';
import { Card, CardHeader, Button, Spinner } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { ErrorState } from '../components/shared/ErrorState';
import { ApprovalDialog } from '../components/shared/ApprovalDialog';
import { HumanInputDialog } from '../components/shared/HumanInputDialog';
import { PathLinkedOutput } from '../components/shared/PathLinkedOutput';
import { ExecutionRerunButton } from '../components/workflow/ExecutionRerunButton';
import {
  useWorkflow, useWorkflowRun,
  usePauseExecution, useResumeExecution, useCancelExecution, useSubmitApproval,
} from '../hooks/useWorkflows';
import { useExecutionWebSocket, type PendingApproval } from '../hooks/useWebSocket';
import { useI18nStore, useT } from '../i18n';
import { formatTimestamp } from '../utils/localeFormat';
import { formatDuration } from '../utils/formatTime';
import { api } from '../api/client';
import type { StepResult, PendingHumanInput } from '../types';
import { stepMetadataSummary } from '../components/workflow/stepMetadata';
import { StepCardFrame, StepCardHeader } from '../components/workflow/StepCardChrome';

function StepCard({ step }: { step: StepResult }) {
  const { provider, model, requestedModel, showRequestedModel } = stepMetadataSummary(step);

  return (
    <StepCardFrame step={step}>
      <div className="pt-0.5">
        {step.status === 'COMPLETED' ? (
          <CheckCircle2 className="w-5 h-5 text-success" />
        ) : step.status === 'FAILED' ? (
          <AlertCircle className="w-5 h-5 text-danger" />
        ) : step.status === 'RUNNING' ? (
          <div className="w-5 h-5 rounded-full border-2 border-accent border-t-transparent animate-spin" />
        ) : (
          <div className="w-5 h-5 rounded-full border-2 border-border" />
        )}
      </div>
      <div className="flex-1 min-w-0">
        <StepCardHeader step={step} />
        {(provider || model || showRequestedModel) && (
          <div className="flex flex-wrap gap-2 mb-2">
            {provider && (
              <span className="px-2 py-0.5 rounded-full bg-surface-raised text-[11px] font-mono text-text-tertiary">
                provider={provider}
              </span>
            )}
            {model && (
              <span className="px-2 py-0.5 rounded-full bg-surface-raised text-[11px] font-mono text-text">
                model={model}
              </span>
            )}
            {showRequestedModel && (
              <span className="px-2 py-0.5 rounded-full bg-warning/10 text-[11px] font-mono text-warning">
                requested={requestedModel}
              </span>
            )}
          </div>
        )}
        {step.output && (
          <pre className="text-xs text-text font-mono bg-surface rounded-lg p-3 overflow-x-auto max-h-48 whitespace-pre-wrap">
            <PathLinkedOutput text={step.output} />
          </pre>
        )}
        {step.error && (
          <p className="text-xs text-danger mt-1">{step.error}</p>
        )}
      </div>
    </StepCardFrame>
  );
}

export function SimpleWorkflowExecution() {
  const { id, runId } = useParams<{ id: string; runId: string }>();
  const navigate = useNavigate();
  const t = useT();
  const locale = useI18nStore((s) => s.locale);

  const { data: workflow } = useWorkflow(id!);
  const { data: run, error: runError, refetch: refetchRun } = useWorkflowRun(id!, runId);
  const { connected, pendingApproval, pendingHumanInput } = useExecutionWebSocket(runId!, id, { liveOutput: false });
  const pauseExecution = usePauseExecution();
  const resumeExecution = useResumeExecution();
  const cancelExecution = useCancelExecution();
  const submitApproval = useSubmitApproval();

  const [recoveredApproval, setRecoveredApproval] = useState<PendingApproval | null>(null);
  const [recoveredHumanInput, setRecoveredHumanInput] = useState<PendingHumanInput | null>(null);

  useEffect(() => {
    if (!runId) return;
    api.get<{
      pending: boolean; blockId?: string; question?: string; options?: string[]; rejectOptions?: string[]; stepIndex?: number;
      type?: string; inputType?: string; defaultValue?: string; variableName?: string;
      required?: boolean; validationPattern?: string | null;
    }>(`/executions/${runId}/approval`).then((data) => {
      if (data.pending && data.blockId) {
        if (data.type === 'human-input') {
          setRecoveredHumanInput({
            blockId: data.blockId,
            prompt: data.question!,
            inputType: (data.inputType as PendingHumanInput['inputType']) || 'text',
            options: data.options || [],
            defaultValue: data.defaultValue || '',
            required: data.required ?? true,
            validationPattern: data.validationPattern ?? null,
            variableName: data.variableName || 'user_input',
            stepIndex: data.stepIndex!,
          });
        } else {
          setRecoveredApproval({
            blockId: data.blockId,
            question: data.question!,
            options: data.options!,
            rejectOptions: data.rejectOptions,
            stepIndex: data.stepIndex!,
          });
        }
      }
    }).catch(() => { /* ignore */ });
  }, [runId]);

  const isRunning = run?.status === 'RUNNING' || run?.status === 'PAUSED';

  // Recovered dialogs only apply while the run is still active
  const runFinished = !!run && !isRunning;
  const activeApproval = pendingApproval ?? (runFinished ? null : recoveredApproval);
  const activeHumanInput = pendingHumanInput ?? (runFinished ? null : recoveredHumanInput);

  if (runError) {
    return <ErrorState message={runError.message} onRetry={() => refetchRun()} />;
  }

  if (!run) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }

  return (
    <div className="space-y-6 animate-fade-in max-w-2xl">
      {/* Header */}
      <div className="flex items-center gap-4">
        <button
          onClick={() => window.history.length > 1 ? navigate(-1) : navigate(`/workflows/${id}`)}
          className="p-2 rounded-lg hover:bg-surface-raised text-text-secondary hover:text-text transition-colors"
          aria-label={t.common.back}
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-3">
            <h1 className="text-xl font-bold text-text truncate">{workflow?.name}</h1>
            <StatusBadge status={run.status} />
            {connected && isRunning && (
              <span className="flex items-center gap-1 text-xs text-success">
                <span className="w-2 h-2 rounded-full bg-success animate-pulse" />
                {t.common.live}
              </span>
            )}
          </div>
          <p className="text-xs text-text-tertiary mt-0.5">
            {formatTimestamp(run.startedAt, locale)} · {t.workflows.execution.duration}: {formatDuration(run.durationMs)}
          </p>
        </div>
        <ExecutionRerunButton run={run} />
        {isRunning && (
          <div className="flex items-center gap-2 shrink-0">
            {run.status === 'RUNNING' && (
              <Button
                variant="secondary" size="sm"
                icon={<Pause className="w-4 h-4" />}
                onClick={() => pauseExecution.mutate(runId!)}
                loading={pauseExecution.isPending}
              >
                {t.workflows.execution.pause}
              </Button>
            )}
            {run.status === 'PAUSED' && (
              <Button
                size="sm"
                icon={<Play className="w-4 h-4" />}
                onClick={() => resumeExecution.mutate(runId!)}
                loading={resumeExecution.isPending}
              >
                {t.workflows.execution.resume}
              </Button>
            )}
            <Button
              variant="danger" size="sm"
              icon={<XCircle className="w-4 h-4" />}
              onClick={() => cancelExecution.mutate(runId!)}
              loading={cancelExecution.isPending}
            >
              {t.workflows.execution.cancel}
            </Button>
          </div>
        )}
      </div>

      {/* Steps */}
      <Card padding={false}>
        <div className="p-5 pb-0">
          <CardHeader title={`${t.workflows.execution.steps} (${run.steps.length})`} />
        </div>
        <div className="px-5 pb-5 divide-y divide-border">
          {run.steps.map((step) => <StepCard key={step.id} step={step} />)}
          {run.steps.length === 0 && (
            <div className="py-8 text-center text-sm text-text-tertiary">
              {isRunning ? t.workflows.execution.waitingSteps : t.workflows.execution.noSteps}
            </div>
          )}
        </div>
      </Card>

      {/* Error */}
      {run.error && Object.keys(run.error).length > 0 && (
        <Card className="border-danger/30 bg-danger-subtle">
          <CardHeader title={t.workflows.execution.error} />
          <pre className="text-sm text-danger font-mono whitespace-pre-wrap">
            {typeof run.error.message === 'string'
              ? run.error.message
              : JSON.stringify(run.error, null, 2)}
          </pre>
        </Card>
      )}

      {/* Approval Dialog */}
      {activeApproval && (
        <ApprovalDialog
          approval={activeApproval}
          loading={submitApproval.isPending}
          onDismiss={() => setRecoveredApproval(null)}
          onSubmit={(answer) => {
            submitApproval.mutate({ runId: runId!, answer }, {
              onSuccess: () => setRecoveredApproval(null),
            });
          }}
        />
      )}

      {/* Human Input Dialog */}
      {activeHumanInput && (
        <HumanInputDialog
          humanInput={activeHumanInput}
          loading={submitApproval.isPending}
          onSubmit={(value) => {
            submitApproval.mutate({ runId: runId!, answer: value }, {
              onSuccess: () => setRecoveredHumanInput(null),
            });
          }}
        />
      )}
    </div>
  );
}
