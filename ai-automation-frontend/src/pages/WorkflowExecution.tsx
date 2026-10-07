import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Clock, Star } from 'lucide-react';
import { Card, CardHeader, Spinner } from '../components/ui';
import { GateStatusBanner } from '../components/shared/GateStatusBanner';
import { ErrorState } from '../components/shared/ErrorState';
import { LogViewer } from '../components/shared/LogViewer';
import { ApprovalDialog } from '../components/workflow/ApprovalDialog';
import { Hint } from '../components/hints/Hint';
import { HumanInputDialog } from '../components/shared/HumanInputDialog';
import { AgenticStepCard } from '../components/workflow/AgenticStepCard';
import { ForEachStepCard } from '../components/workflow/ForEachStepCard';
import { InsightsPanel } from '../components/shared/InsightsPanel';
import { ConversionSuggestion } from '../components/shared/ConversionSuggestion';
import { useWorkflow, useWorkflowRun, useWorkflowBpmn, useSubmitApproval } from '../hooks/useWorkflows';
import { ExecutionDiagramPanel } from '../components/workflow/ExecutionDiagramPanel';
import { ExecutionStepCard } from '../components/workflow/ExecutionStepCard';
import { stepBlockType } from '../components/workflow/stepMetadata';
import { RunHeader } from '../components/workflow/RunHeader';
import { RunNotesCard } from '../components/workflow/RunNotesCard';
import { RunContainerPanel } from '../components/workflow/RunContainerPanel';
import { RunInputVariables } from '../components/workflow/RunInputVariables';
import { RunPendingTodosPanel } from '../components/workflow/RunPendingTodosPanel';
import { RunProvenance } from '../components/workflow/RunProvenance';
import { useExecutionWebSocket } from '../hooks/useWebSocket';
import { useRunPendingInput } from '../hooks/useRunPendingInput';
import { useRunDiagramSync } from '../components/workflow/useRunDiagramSync';
import { useRateRun } from '../hooks/useLearning';
import { useModuleEnabled } from '../hooks/useModules';
import { useI18nStore, useT } from '../i18n';
import { formatDuration } from '../utils/formatTime';
import type { PendingTodoItem } from '../types';

export function WorkflowExecution() {
  const { id, runId } = useParams<{ id: string; runId: string }>();
  const t = useT();
  const locale = useI18nStore((s) => s.locale);

  const { data: workflow } = useWorkflow(id!);
  const { data: run, error: runError, refetch: refetchRun } = useWorkflowRun(id!, runId);
  const { logs, connected, pendingApproval, pendingHumanInput, inputSignal, agenticStates, forEachStates, liveLogSource } = useExecutionWebSocket(runId!, id);
  const learningModule = useModuleEnabled('learning');
  const conversionModule = useModuleEnabled('conversion');
  const rateRun = useRateRun();
  const submitApproval = useSubmitApproval();

  // Unsaved notes live here, not in the card: they survive a refetch error or the spinner. They
  // belong to one run, so a draft never follows "Run again" onto the next run on this page.
  const [notes, setNotes] = useState<{ runId: string | undefined; draft: string | null }>({ runId, draft: null });
  const notesDraft = notes.runId === runId ? notes.draft : null;
  const {
    approval: activeApproval, humanInput: activeHumanInput, dismissedApproval, markAnswered, dismiss, reopen,
  } = useRunPendingInput(runId, run, { connected, approval: pendingApproval, humanInput: pendingHumanInput, inputSignal });

  const isRunning = run?.status === 'RUNNING' || run?.status === 'PAUSED';

  // Only load the BPMN diagram when there is a run with steps (flow visualization)
  const { data: bpmnXml } = useWorkflowBpmn(id!, (run?.steps?.length ?? 0) > 0);
  const { statusByBlock, stepHighlight, selectStep } = useRunDiagramSync(run?.steps, isRunning);

  const handleRate = (rating: number) => {
    rateRun.mutate({ runId: runId!, rating, workflowId: id! });
  };

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
    <div className="space-y-6 animate-fade-in">
      <RunHeader run={run} workflow={workflow} workflowId={id!} connected={connected} isRunning={isRunning}
        onOpenApproval={dismissedApproval ? reopen : undefined} />

      <GateStatusBanner run={run} />

      {run.status === 'FAILED' && <Hint id="run-failed" />}

      {/* Duration + rating */}
      <div className="flex items-center gap-6 text-sm">
        <div className="flex items-center gap-2 text-text-secondary">
          <Clock className="w-4 h-4" />
          <span>{t.workflows.execution.duration}: {formatDuration(run.durationMs, locale)}</span>
        </div>
        {run.status === 'COMPLETED' && (
          <div className="flex items-center gap-1">
            <span className="text-text-secondary text-sm mr-1">{t.workflows.execution.rate}</span>
            {[1, 2, 3, 4, 5].map((star) => {
              const filled = !!run.userRating && star <= run.userRating;
              return (
                <button
                  key={star}
                  onClick={() => handleRate(star)}
                  aria-label={`${star}/5`}
                  aria-pressed={filled}
                  className={`transition-colors ${filled ? 'text-warning' : 'text-text-tertiary hover:text-warning'}`}
                >
                  {/* An icon, not the text glyph U+2605: the default UI font lacks it, and without a
                      fallback font the browser draws an empty box instead. */}
                  <Star className="w-5 h-5" fill={filled ? 'currentColor' : 'none'} aria-hidden="true" />
                </button>
              );
            })}
          </div>
        )}
      </div>

      {/* Provenance */}
      <RunProvenance run={run} />

      {/* Input Variables */}
      {run.inputContext && Object.keys(run.inputContext).length > 0 && (
        <RunInputVariables inputContext={run.inputContext} />
      )}

      {/* Pending TODOs approval panel */}
      <RunPendingTodosPanel runId={runId!} pendingTodos={run.metadata?.pending_todos as PendingTodoItem[] | undefined} />

      <Hint id="run-steps" />

      {/* Ablauf-Diagramm mit Live-Status je Block */}
      {bpmnXml && run.steps.length > 0 && (
        <ExecutionDiagramPanel xml={bpmnXml} statusByBlock={statusByBlock} onSelectBlock={selectStep} />
      )}

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Steps */}
        <Card padding={false}>
          <div className="p-5 pb-0">
            <CardHeader title={`${t.workflows.execution.steps} (${run.steps.length})`} />
          </div>
          <div className="px-5 pb-5 divide-y divide-border">
            {run.steps.map((step) => (
              <div
                key={step.id}
                id={`exec-step-${step.stepIndex}`}
                className={stepHighlight?.index === step.stepIndex ? 'ring-2 ring-accent ring-inset rounded-lg bg-accent/5' : ''}
              >
                {stepBlockType(step) === 'agentic' ? (
                  <AgenticStepCard step={step} agenticState={agenticStates.get(step.stepIndex)} runActive={isRunning} />
                ) : stepBlockType(step) === 'foreach' ? (
                  <ForEachStepCard step={step} forEachState={forEachStates.get(step.stepIndex)} runActive={isRunning} />
                ) : (
                  <ExecutionStepCard step={step} runActive={isRunning} />
                )}
              </div>
            ))}
            {run.steps.length === 0 && (
              <div className="py-8 text-center text-sm text-text-tertiary">
                {isRunning ? t.workflows.execution.waitingSteps : t.workflows.execution.noSteps}
              </div>
            )}
          </div>
        </Card>

        {/* Live logs */}
        <Card>
          <CardHeader
            title={t.workflows.execution.logs}
            action={liveLogSource === 'stored'
              ? <span className="text-xs text-text-tertiary">{t.workflows.execution.liveLogStored}</span>
              : !isRunning && liveLogSource === 'none'
                ? <span className="text-xs text-text-tertiary" title={t.workflows.execution.noLiveLogStored}>
                    {t.workflows.execution.noLiveLogStoredShort}
                  </span>
                : undefined}
          />
          <LogViewer
            logs={logs}
            maxHeight="500px"
            emptyText={!isRunning && liveLogSource === 'none' ? t.workflows.execution.noLiveLogStored : undefined}
          />
        </Card>
      </div>

      {/* Container interaction — visible when running or paused */}
      {isRunning && <RunContainerPanel runId={runId!} />}

      {/* Execution Insights */}
      {!isRunning && run.steps.length > 0 && learningModule && (
        <InsightsPanel runId={runId!} />
      )}

      {/* Agentic-to-Workflow Conversion */}
      {run.status === 'COMPLETED' && conversionModule && run.steps.some(s => stepBlockType(s) === 'agentic') && (
        <ConversionSuggestion runId={runId!} />
      )}

      {/* Notes */}
      <RunNotesCard
        key={run.id}
        runId={runId!}
        savedNotes={(run.metadata?.notes as string) ?? ''}
        draft={notesDraft}
        onDraftChange={(draft) => setNotes({ runId, draft })}
      />

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
          onDismiss={() => dismiss(activeApproval)}
          onSubmit={(answer) => {
            submitApproval.mutate({ runId: runId!, answer }, {
              onSuccess: () => markAnswered(activeApproval),
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
              onSuccess: () => markAnswered(activeHumanInput),
            });
          }}
        />
      )}
    </div>
  );
}
