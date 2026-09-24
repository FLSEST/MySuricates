import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ArrowLeft, Pause, Play, XCircle, CheckCircle2, AlertCircle, CircleSlash,
} from 'lucide-react';
import { Button, Spinner } from '../components/ui';
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
import type { StepResult, PendingHumanInput, RunStatus } from '../types';
import { StepCardFrame, StepCardHeader } from '../components/workflow/StepCardChrome';
import { Hint } from '../components/hints/Hint';
import { Disclosure } from '../components/simple/Disclosure';
import { SectionLabel } from '../components/simple/kit';
import { RunResultCard } from '../components/simple/RunResultCard';
import { RunFailureCard } from '../components/simple/RunFailureCard';
import { stepDisplayName } from '../utils/runResult';

function StepStatusIcon({ status }: { status: StepResult['status'] }) {
  if (status === 'COMPLETED') return <CheckCircle2 className="w-5 h-5 text-success" />;
  if (status === 'FAILED') return <AlertCircle className="w-5 h-5 text-danger" />;
  if (status === 'RUNNING') {
    return <div className="w-5 h-5 rounded-full border-2 border-accent border-t-transparent animate-spin" />;
  }
  return <div className="w-5 h-5 rounded-full border-2 border-border" />;
}

/** One step in the details: what it was and what it printed — no provider/model facts (those live in Pro). */
function StepCard({ step }: { step: StepResult }) {
  return (
    <StepCardFrame step={step}>
      <div className="pt-0.5">
        <StepStatusIcon status={step.status} />
      </div>
      <div className="flex-1 min-w-0">
        <StepCardHeader step={step} />
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

/** While the run is going: which step it is at, nothing more. */
function ProgressList({ steps, emptyText }: { steps: StepResult[]; emptyText: string }) {
  const t = useT();
  const sorted = [...steps].sort((a, b) => a.stepIndex - b.stepIndex);
  if (sorted.length === 0) {
    return <p className="py-4 text-sm text-text-tertiary">{emptyText}</p>;
  }
  return (
    <ol className="space-y-2" data-testid="run-progress">
      {sorted.map((step) => (
        <li key={step.id} className="flex items-center gap-3 rounded-xl border border-border bg-surface px-4 py-2.5">
          <StepStatusIcon status={step.status} />
          <span className="min-w-0 truncate text-sm text-text">{stepDisplayName(step, t)}</span>
        </li>
      ))}
    </ol>
  );
}

const SUCCESS_STATUSES: ReadonlySet<RunStatus> = new Set(['COMPLETED', 'COMPLETED_WITH_ERRORS']);
const FAILURE_STATUSES: ReadonlySet<RunStatus> = new Set(['FAILED', 'INTERRUPTED']);
const ACTIVE_STATUSES: ReadonlySet<RunStatus> = new Set(['PENDING', 'RUNNING', 'PAUSED']);

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
        {/* A failed run offers its rerun in the failure card below — one button, not two. */}
        {!FAILURE_STATUSES.has(run.status) && <ExecutionRerunButton run={run} />}
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

      {ACTIVE_STATUSES.has(run.status) ? (
        <section aria-label={t.simple.result.progress}>
          <SectionLabel>{t.simple.result.progress}</SectionLabel>
          <ProgressList steps={run.steps} emptyText={t.workflows.execution.waitingSteps} />
        </section>
      ) : (
        <>
          <Hint id="simple-result" />
          {SUCCESS_STATUSES.has(run.status) && <RunResultCard run={run} />}
          {FAILURE_STATUSES.has(run.status) && <RunFailureCard run={run} />}
          {run.status === 'CANCELLED' && (
            <p className="flex items-center gap-2 text-sm text-text-secondary">
              <CircleSlash className="w-4 h-4 shrink-0" aria-hidden="true" />
              {t.simple.result.cancelled}
            </p>
          )}
          <Disclosure label={`${t.simple.result.details} · ${run.steps.length}`}>
            <div className="divide-y divide-border" data-testid="run-details">
              {[...run.steps].sort((a, b) => a.stepIndex - b.stepIndex).map((step) => <StepCard key={step.id} step={step} />)}
              {run.steps.length === 0 && (
                <div className="py-8 text-center text-sm text-text-tertiary">{t.workflows.execution.noSteps}</div>
              )}
            </div>
          </Disclosure>
        </>
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
