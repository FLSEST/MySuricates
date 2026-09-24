import { useState, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Pause, Play, XCircle, Clock, FolderOpen, ListTodo, Save, Trash2 } from 'lucide-react';
import { Card, CardHeader, Button, Spinner } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { GateStatusBadge } from '../components/shared/GateStatusBadge';
import { GateStatusBanner } from '../components/shared/GateStatusBanner';
import { ErrorState } from '../components/shared/ErrorState';
import { LogViewer } from '../components/shared/LogViewer';
import { ApprovalDialog } from '../components/shared/ApprovalDialog';
import { Hint } from '../components/hints/Hint';
import { HumanInputDialog } from '../components/shared/HumanInputDialog';
import { AgenticStepCard } from '../components/shared/AgenticStepCard';
import { ForEachStepCard } from '../components/shared/ForEachStepCard';
import { InsightsPanel } from '../components/shared/InsightsPanel';
import { ConversionSuggestion } from '../components/shared/ConversionSuggestion';
import {
  useWorkflow, useWorkflowRun, useWorkflowBpmn,
  usePauseExecution, useResumeExecution, useCancelExecution, useSubmitApproval,
  useResumeFromStep, useUpdateRunNotes, useDeleteRun,
} from '../hooks/useWorkflows';
import { type RunStatus, type DiagramSelection } from '../components/workflow/BpmnDiagramView';
import { ExecutionDiagramPanel } from '../components/workflow/ExecutionDiagramPanel';
import { ExecutionStepCard } from '../components/workflow/ExecutionStepCard';
import { latestStepByBlock, stepBlockType } from '../components/workflow/stepMetadata';
import { ExecutionRerunButton } from '../components/workflow/ExecutionRerunButton';
import { RunContainerPanel } from '../components/workflow/RunContainerPanel';
import { RunInputVariables } from '../components/workflow/RunInputVariables';
import { RunPendingTodosPanel } from '../components/workflow/RunPendingTodosPanel';
import { RunProvenance } from '../components/workflow/RunProvenance';
import { useCreateTodo } from '../hooks/useTodos';
import { useExecutionWebSocket } from '../hooks/useWebSocket';
import { useRunInputRecovery } from '../hooks/useRunInputRecovery';
import { useRateRun } from '../hooks/useLearning';
import { useModuleEnabled } from '../hooks/useModules';
import { useRunDeletionAllowed } from '../hooks/useSystemCapabilities';
import { useToast } from '../hooks/useToast';
import { useI18nStore, useT } from '../i18n';
import { formatTimestamp } from '../utils/localeFormat';
import { formatDuration } from '../utils/formatTime';
import { openPath } from '../api/client';
import type { PendingTodoItem } from '../types';

export function WorkflowExecution() {
  const { id, runId } = useParams<{ id: string; runId: string }>();
  const navigate = useNavigate();
  const t = useT();
  const locale = useI18nStore((s) => s.locale);

  const { data: workflow } = useWorkflow(id!);
  const { data: run, error: runError, refetch: refetchRun } = useWorkflowRun(id!, runId);
  const { logs, connected, pendingApproval, pendingHumanInput, agenticStates, forEachStates } = useExecutionWebSocket(runId!, id);
  const toast = useToast();
  const learningModule = useModuleEnabled('learning');
  const conversionModule = useModuleEnabled('conversion');
  const rateRun = useRateRun();
  const pauseExecution = usePauseExecution();
  const resumeExecution = useResumeExecution();
  const cancelExecution = useCancelExecution();
  const deleteRun = useDeleteRun();
  const runDeletionAllowed = useRunDeletionAllowed();
  const resumeFromStep = useResumeFromStep();
  const submitApproval = useSubmitApproval();
  const createTodo = useCreateTodo();
  const updateNotes = useUpdateRunNotes();

  const [notesText, setNotesText] = useState<string | null>(null);
  const { recoveredApproval, recoveredHumanInput, clearRecoveredApproval, clearRecoveredHumanInput } = useRunInputRecovery(runId);

  const isRunning = run?.status === 'RUNNING' || run?.status === 'PAUSED';

  // BPMN-Diagramm nur laden, wenn es einen Run mit Schritten gibt (Ablauf-Visualisierung)
  const { data: bpmnXml } = useWorkflowBpmn(id!, (run?.steps?.length ?? 0) > 0);
  // Diagramm-Kästen sind nach Block-Position nummeriert, Steps nach stepIndex — der läuft nach jedem
  // Wrapper-Block (foreach, while, …) voraus, deshalb die Zuordnung über latestStepByBlock.
  const stepByBlock = useMemo(() => latestStepByBlock(run?.steps ?? []), [run?.steps]);
  const statusByBlock = useMemo(() => {
    const map = new Map<number, RunStatus>();
    for (const [blockIndex, step] of stepByBlock) {
      const s = step.status === 'RUNNING' ? 'running' : step.status === 'COMPLETED' ? 'completed' : step.status === 'FAILED' ? 'failed' : undefined;
      if (s) map.set(blockIndex, s);
    }
    return map;
  }, [stepByBlock]);

  // Klick im Diagramm scrollt zum Step des Blocks und hebt ihn kurz hervor
  const [stepHighlight, setStepHighlight] = useState<{ index: number; nonce: number } | null>(null);
  const handleSelectStep = (sel: DiagramSelection) => {
    const step = stepByBlock.get(sel.blockIndex);
    if (!step) return; // Block ist (noch) nicht gelaufen
    document.getElementById(`exec-step-${step.stepIndex}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setStepHighlight((prev) => ({ index: step.stepIndex, nonce: (prev?.nonce ?? 0) + 1 }));
    window.setTimeout(() => setStepHighlight(null), 1800);
  };

  // Recovered dialogs only apply while the run is still active
  const runFinished = !!run && !isRunning;
  const activeApproval = pendingApproval ?? (runFinished ? null : recoveredApproval);
  const activeHumanInput = pendingHumanInput ?? (runFinished ? null : recoveredHumanInput);

  const handleRate = (rating: number) => {
    rateRun.mutate({ runId: runId!, rating, workflowId: id! });
  };

  const handleCreateFailedTodo = () => {
    if (!run) return;
    const failedStep = run.steps?.find(s => s.status === 'FAILED');
    const stepInfo = failedStep ? `Step ${failedStep.stepIndex + 1} (${stepBlockType(failedStep)})` : null;

    const descParts: string[] = [];
    descParts.push(`**Workflow:** ${workflow?.name || run.workflowName}`);
    descParts.push(`**Run:** [${formatTimestamp(run.startedAt, locale)}](/workflows/${id}/run/${runId})`);
    if (stepInfo) descParts.push(`**Fehlgeschlagener Step:** ${stepInfo}`);
    if (run.error?.message) descParts.push(`**Fehler:** ${run.error.message}`);
    if (failedStep?.error) descParts.push(`**Step-Fehler:** ${failedStep.error}`);

    createTodo.mutate({
      title: `${workflow?.name || run.workflowName}: Run fehlgeschlagen`,
      description: descParts.join('\n'),
      priority: 'HIGH',
      tags: ['failed-run'],
      projectId: workflow?.projectId || undefined,
      source: 'WORKFLOW',
      workflowRunId: run.id,
    });
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
      {/* Header */}
      <div className="flex items-center gap-4">
        <button
          onClick={() => window.history.length > 1 ? navigate(-1) : navigate(`/workflows/${id}`)}
          className="p-2 rounded-lg hover:bg-surface-raised text-text-secondary hover:text-text transition-colors"
          title={t.common.back}
          aria-label={t.common.back}
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div className="flex-1">
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold text-text">{t.workflows.execution.title}</h1>
            <StatusBadge status={run.status} />
            <GateStatusBadge run={run} />
            {connected && isRunning && (
              <span className="flex items-center gap-1 text-xs text-success">
                <span className="w-2 h-2 rounded-full bg-success animate-pulse" />
                {t.common.live}
              </span>
            )}
          </div>
          <p className="text-sm text-text-secondary mt-0.5 flex items-center gap-2">
            <span>
              <button
                onClick={() => navigate(`/workflows/${id}`)}
                className="text-accent hover:text-accent-hover hover:underline transition-colors"
              >
                {workflow?.name}
              </button>
              {' '}&middot; {formatTimestamp(run.startedAt, locale)}
            </span>
            {run.projectPath && (
              <button
                onClick={() => openPath(run.projectPath!).catch(() => toast.error(t.workflows.execution.openPathFailed))}
                className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium bg-surface-raised hover:bg-surface-raised/80 text-text-secondary hover:text-text transition-colors"
                title={run.projectPath}
              >
                <FolderOpen className="w-3.5 h-3.5" />
                {t.workflows.execution.openFolder}
              </button>
            )}
          </p>
        </div>
        {(run.status === 'INTERRUPTED' || run.status === 'FAILED') && (
          <div className="flex items-center gap-2">
            {run.status === 'FAILED' && (
              <Button
                variant="secondary" size="sm"
                icon={<ListTodo className="w-4 h-4" />}
                onClick={handleCreateFailedTodo}
                loading={createTodo.isPending}
              >
                {t.workflows.execution.createTodo}
              </Button>
            )}
            {run.status === 'INTERRUPTED' && run.steps?.some(s => s.status === 'COMPLETED') && (
              <Button
                variant="primary" size="sm"
                icon={<Play className="w-4 h-4" />}
                onClick={() => resumeFromStep.mutate(runId!)}
                loading={resumeFromStep.isPending}
              >
                {t.workflows.execution.resumeFromStep}
              </Button>
            )}
          </div>
        )}
        <ExecutionRerunButton run={run} />
        {!isRunning && runDeletionAllowed && (
          <Button
            variant="secondary" size="sm"
            icon={<Trash2 className="w-4 h-4" />}
            loading={deleteRun.isPending}
            onClick={() => {
              if (!window.confirm(t.runs.deleteConfirm)) return;
              // Der Lauf existiert gleich nicht mehr — weg von seiner Detailseite.
              deleteRun.mutate(runId!, { onSuccess: () => navigate(`/workflows/${id}`) });
            }}
          >
            {t.runs.delete}
          </Button>
        )}
        {isRunning && (
          <div className="flex items-center gap-2">
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

      <GateStatusBanner run={run} />

      {run.status === 'FAILED' && <Hint id="run-failed" />}

      {/* Duration + rating */}
      <div className="flex items-center gap-6 text-sm">
        <div className="flex items-center gap-2 text-text-secondary">
          <Clock className="w-4 h-4" />
          <span>{t.workflows.execution.duration}: {formatDuration(run.durationMs)}</span>
        </div>
        {run.status === 'COMPLETED' && (
          <div className="flex items-center gap-1">
            <span className="text-text-secondary text-sm mr-1">{t.workflows.execution.rate}</span>
            {[1, 2, 3, 4, 5].map((star) => (
              <button
                key={star}
                onClick={() => handleRate(star)}
                aria-label={`${star}/5`}
                className={`text-lg transition-colors ${
                  run.userRating && star <= run.userRating ? 'text-warning' : 'text-text-tertiary hover:text-warning'
                }`}
              >
                ★
              </button>
            ))}
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
        <ExecutionDiagramPanel xml={bpmnXml} statusByBlock={statusByBlock} onSelectBlock={handleSelectStep} />
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
                  <AgenticStepCard step={step} agenticState={agenticStates.get(step.stepIndex)} />
                ) : stepBlockType(step) === 'foreach' ? (
                  <ForEachStepCard step={step} forEachState={forEachStates.get(step.stepIndex)} />
                ) : (
                  <ExecutionStepCard step={step} />
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
          <CardHeader title={t.workflows.execution.logs} />
          <LogViewer logs={logs} maxHeight="500px" />
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
      <Card>
        <CardHeader
          title={t.workflows.execution.notes}
          action={
            (notesText ?? '') !== ((run.metadata?.notes as string) ?? '') ? (
              <Button
                size="sm"
                variant="ghost"
                icon={<Save className="w-3.5 h-3.5" />}
                onClick={() => {
                  updateNotes.mutate({ runId: runId!, notes: notesText ?? '' }, {
                    onSuccess: () => toast.success(t.workflows.execution.notesSaved),
                  });
                }}
                loading={updateNotes.isPending}
              >
                {t.common.save}
              </Button>
            ) : undefined
          }
        />
        <textarea
          className="w-full bg-surface border border-border rounded-lg p-3 text-sm text-text placeholder-text-tertiary resize-y min-h-[60px] focus:outline-none focus:ring-1 focus:ring-accent"
          placeholder={t.workflows.execution.notesPlaceholder}
          value={notesText ?? (run.metadata?.notes as string) ?? ''}
          onChange={(e) => setNotesText(e.target.value)}
          onBlur={() => {
            const current = (run.metadata?.notes as string) ?? '';
            if (notesText !== null && notesText !== current) {
              updateNotes.mutate({ runId: runId!, notes: notesText });
            }
          }}
        />
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
          onDismiss={clearRecoveredApproval}
          onSubmit={(answer) => {
            submitApproval.mutate({ runId: runId!, answer }, {
              onSuccess: () => {
                clearRecoveredApproval();
              },
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
              onSuccess: () => {
                clearRecoveredHumanInput();
              },
            });
          }}
        />
      )}
    </div>
  );
}
