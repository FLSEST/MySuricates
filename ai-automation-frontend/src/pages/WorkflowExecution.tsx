import { useState, useEffect, useMemo, lazy, Suspense } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ArrowLeft, Pause, Play, XCircle, Clock, Send, TerminalSquare,
  FolderOpen, ListTodo, Save, Trash2,
} from 'lucide-react';
import { Card, CardHeader, Button, Spinner, Input } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { GateStatusBadge } from '../components/shared/GateStatusBadge';
import { GateStatusBanner } from '../components/shared/GateStatusBanner';
import { ErrorState } from '../components/shared/ErrorState';
import { LogViewer } from '../components/shared/LogViewer';
import { ApprovalDialog } from '../components/shared/ApprovalDialog';
import { HumanInputDialog } from '../components/shared/HumanInputDialog';
import { AgenticStepCard } from '../components/shared/AgenticStepCard';
import { ForEachStepCard } from '../components/shared/ForEachStepCard';
import { InsightsPanel } from '../components/shared/InsightsPanel';
import { ConversionSuggestion } from '../components/shared/ConversionSuggestion';
import {
  useWorkflow, useWorkflowRuns, useWorkflowBpmn,
  usePauseExecution, useResumeExecution, useCancelExecution, useRunCommand, useSubmitApproval,
  useRetryExecution, useApproveTodos, useResumeFromStep, useUpdateRunNotes, useDeleteRun,
} from '../hooks/useWorkflows';
import { type RunStatus, type DiagramSelection } from '../components/workflow/BpmnDiagramView';
import { ExecutionDiagramPanel } from '../components/workflow/ExecutionDiagramPanel';
import { ExecutionStepCard } from '../components/workflow/ExecutionStepCard';
import { useCreateTodo } from '../hooks/useTodos';
import { useExecutionWebSocket } from '../hooks/useWebSocket';
import { useRateRun } from '../hooks/useLearning';
import { useToast } from '../hooks/useToast';
import { useT } from '../i18n';
import { formatDuration } from '../utils/formatTime';
import { api, openPath } from '../api/client';
import type { WorkflowRun, PendingHumanInput, PendingTodoItem } from '../types';

const TerminalPanel = lazy(() => import('../components/shared/TerminalPanel').then(m => ({ default: m.TerminalPanel })));

export function WorkflowExecution() {
  const { id, runId } = useParams<{ id: string; runId: string }>();
  const navigate = useNavigate();
  const t = useT();

  const { data: workflow } = useWorkflow(id!);
  const { data: runs, error: runsError, refetch: refetchRuns } = useWorkflowRuns(id!);
  const { logs, connected, pendingApproval, pendingHumanInput, agenticStates, forEachStates } = useExecutionWebSocket(runId!, id);
  const toast = useToast();
  const rateRun = useRateRun();
  const pauseExecution = usePauseExecution();
  const resumeExecution = useResumeExecution();
  const cancelExecution = useCancelExecution();
  const retryExecution = useRetryExecution();
  const deleteRun = useDeleteRun();
  const resumeFromStep = useResumeFromStep();
  const runCommand = useRunCommand();
  const submitApproval = useSubmitApproval();
  const approveTodos = useApproveTodos();
  const createTodo = useCreateTodo();
  const updateNotes = useUpdateRunNotes();

  const [command, setCommand] = useState('');
  const [notesText, setNotesText] = useState<string | null>(null);
  const [containerTab, setContainerTab] = useState<'command' | 'terminal'>('command');
  const [recoveredApproval, setRecoveredApproval] = useState<{
    blockId: string; question: string; options: string[]; stepIndex: number;
  } | null>(null);
  const [recoveredHumanInput, setRecoveredHumanInput] = useState<PendingHumanInput | null>(null);

  // Recovery: check for pending approval/human-input on page load/reload
  useEffect(() => {
    if (!runId) return;
    api.get<{
      pending: boolean; blockId?: string; question?: string; options?: string[]; stepIndex?: number;
      type?: string; inputType?: string; defaultValue?: string; variableName?: string;
      required?: boolean; validationPattern?: string | null;
    }>(
      `/executions/${runId}/approval`
    ).then((data) => {
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
            stepIndex: data.stepIndex!,
          });
        }
      }
    }).catch(() => { /* ignore */ });
  }, [runId]);

  const run: WorkflowRun | undefined = runs?.content?.find((r) => r.id === runId);
  const isRunning = run?.status === 'RUNNING' || run?.status === 'PAUSED';

  // BPMN-Diagramm nur laden, wenn es einen Run mit Schritten gibt (Ablauf-Visualisierung)
  const { data: bpmnXml } = useWorkflowBpmn(id!, (run?.steps?.length ?? 0) > 0);
  const statusByBlock = useMemo(() => {
    const map = new Map<number, RunStatus>();
    for (const step of run?.steps ?? []) {
      const s = step.status === 'RUNNING' ? 'running' : step.status === 'COMPLETED' ? 'completed' : step.status === 'FAILED' ? 'failed' : undefined;
      if (s) map.set(step.stepIndex, s);
    }
    return map;
  }, [run?.steps]);

  // Klick im Diagramm scrollt zum Step und hebt ihn kurz hervor
  const [stepHighlight, setStepHighlight] = useState<{ index: number; nonce: number } | null>(null);
  const handleSelectStep = (sel: DiagramSelection) => {
    document.getElementById(`exec-step-${sel.blockIndex}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setStepHighlight((prev) => ({ index: sel.blockIndex, nonce: (prev?.nonce ?? 0) + 1 }));
    window.setTimeout(() => setStepHighlight(null), 1800);
  };

  // Clear recovered dialogs when run is no longer active
  useEffect(() => {
    if (run && !isRunning) {
      setRecoveredApproval(null);
      setRecoveredHumanInput(null);
    }
  }, [run, isRunning]);

  const activeApproval = pendingApproval ?? recoveredApproval;
  const activeHumanInput = pendingHumanInput ?? recoveredHumanInput;

  const handleRate = (rating: number) => {
    rateRun.mutate({ runId: runId!, rating, workflowId: id! });
  };

  const handleCreateFailedTodo = () => {
    if (!run) return;
    const failedStep = run.steps?.find(s => s.status === 'FAILED');
    const stepInfo = failedStep ? `Step ${failedStep.stepIndex + 1} (${failedStep.blockType})` : null;

    const descParts: string[] = [];
    descParts.push(`**Workflow:** ${workflow?.name || run.workflowName}`);
    descParts.push(`**Run:** [${new Date(run.startedAt).toLocaleString()}](/workflows/${id}/run/${runId})`);
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

  if (runsError) {
    return <ErrorState message={runsError.message} onRetry={() => refetchRuns()} />;
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
              {' '}&middot; {new Date(run.startedAt).toLocaleString()}
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
            <Button
              variant="secondary" size="sm"
              icon={<Play className="w-4 h-4" />}
              onClick={() => retryExecution.mutate(runId!)}
              loading={retryExecution.isPending}
            >
              {t.workflows.execution.retry}
            </Button>
          </div>
        )}
        {!isRunning && (
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

      {/* Input Variables */}
      {run.inputContext && Object.keys(run.inputContext).length > 0 && (
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs font-medium text-text-secondary">{t.workflows.execution.inputVariables}:</span>
          {Object.entries(run.inputContext).map(([key, value]) => {
            const strVal = String(value);
            const isPath = /^\/(?:[\w.-]+\/)+[\w.-]*$/.test(strVal);
            return (
              <span
                key={key}
                className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-surface-raised text-xs font-mono"
              >
                <span className="text-text-secondary">{key}</span>
                <span className="text-text-tertiary">=</span>
                <span className="text-text">{strVal}</span>
                {isPath && (
                  <button
                    onClick={() => openPath(strVal).catch(() => toast.error(t.workflows.execution.openPathFailed))}
                    className="ml-0.5 text-text-tertiary hover:text-accent transition-colors"
                    title={t.workflows.execution.openFolder}
                  >
                    <FolderOpen className="w-3 h-3" />
                  </button>
                )}
              </span>
            );
          })}
        </div>
      )}

      {/* Pending TODOs approval panel */}
      {(() => {
        const pendingTodos = run.metadata?.pending_todos as PendingTodoItem[] | undefined;
        if (!pendingTodos?.length) return null;
        return (
          <div className="rounded-xl border border-accent/40 bg-accent/5 p-4 space-y-3">
            <div className="flex items-center gap-2">
              <ListTodo className="w-5 h-5 text-accent" />
              <h3 className="text-sm font-semibold text-text">{t.workflows.pendingTodos}</h3>
            </div>
            <p className="text-sm text-text-secondary">{t.workflows.pendingTodosHint}</p>
            <div className="space-y-2">
              {pendingTodos.map((todo, i) => (
                <div key={i} className="flex items-start gap-2 bg-surface rounded-lg px-3 py-2">
                  <span className={`text-xs font-medium px-1.5 py-0.5 rounded shrink-0 ${
                    todo.priority === 'HIGH' ? 'bg-danger/10 text-danger' :
                    todo.priority === 'MEDIUM' ? 'bg-warning/10 text-warning' :
                    'bg-surface-raised text-text-tertiary'
                  }`}>{todo.priority}</span>
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-text">{todo.title}</p>
                    {todo.description && (
                      <p className="text-xs text-text-secondary mt-0.5 line-clamp-2">{todo.description}</p>
                    )}
                  </div>
                </div>
              ))}
            </div>
            <div className="flex items-center gap-2 pt-1">
              <Button
                size="sm"
                onClick={() => approveTodos.mutate({ runId: runId!, approved: true })}
                loading={approveTodos.isPending}
              >
                {t.workflows.approveTodos}
              </Button>
              <Button
                size="sm"
                variant="ghost"
                onClick={() => approveTodos.mutate({ runId: runId!, approved: false })}
                loading={approveTodos.isPending}
              >
                {t.workflows.rejectTodos}
              </Button>
            </div>
          </div>
        );
      })()}

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
                {step.blockType === 'agentic' ? (
                  <AgenticStepCard step={step} agenticState={agenticStates.get(step.stepIndex)} />
                ) : step.blockType === 'foreach' ? (
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
      {isRunning && (
        <Card>
          <div className="flex items-center gap-1 mb-4 border-b border-border">
            <button
              onClick={() => setContainerTab('command')}
              className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                containerTab === 'command'
                  ? 'border-accent text-accent'
                  : 'border-transparent text-text-secondary hover:text-text'
              }`}
            >
              <span className="flex items-center gap-2">
                <Send className="w-4 h-4" />
                {t.workflows.execution.command}
              </span>
            </button>
            <button
              onClick={() => setContainerTab('terminal')}
              className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                containerTab === 'terminal'
                  ? 'border-accent text-accent'
                  : 'border-transparent text-text-secondary hover:text-text'
              }`}
            >
              <span className="flex items-center gap-2">
                <TerminalSquare className="w-4 h-4" />
                {t.workflows.execution.terminal.title}
              </span>
            </button>
          </div>

          {containerTab === 'command' && (
            <div className="flex gap-2">
              <div className="flex-1">
                <Input
                  value={command}
                  onChange={(e) => setCommand(e.target.value)}
                  placeholder={t.workflows.execution.commandPlaceholder}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && command.trim()) {
                      runCommand.mutate({ runId: runId!, command: command.trim() });
                      setCommand('');
                    }
                  }}
                />
              </div>
              <Button
                icon={<Send className="w-4 h-4" />}
                onClick={() => {
                  if (command.trim()) {
                    runCommand.mutate({ runId: runId!, command: command.trim() });
                    setCommand('');
                  }
                }}
                loading={runCommand.isPending}
                disabled={!command.trim()}
              >
                {t.workflows.execution.sendCommand}
              </Button>
            </div>
          )}

          {containerTab === 'terminal' && (
            <Suspense fallback={<Spinner text={t.common.loading} />}>
              <TerminalPanel runId={runId!} />
            </Suspense>
          )}
        </Card>
      )}

      {/* Execution Insights */}
      {!isRunning && run.steps.length > 0 && (
        <InsightsPanel runId={runId!} />
      )}

      {/* Agentic-to-Workflow Conversion */}
      {run.status === 'COMPLETED' && run.steps.some(s => s.blockType === 'agentic') && (
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
          onDismiss={() => setRecoveredApproval(null)}
          onSubmit={(answer) => {
            submitApproval.mutate({ runId: runId!, answer }, {
              onSuccess: () => {
                setRecoveredApproval(null);
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
                setRecoveredHumanInput(null);
              },
            });
          }}
        />
      )}
    </div>
  );
}
