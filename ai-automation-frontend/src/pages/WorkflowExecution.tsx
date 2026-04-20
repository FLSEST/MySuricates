import { useState, useEffect, lazy, Suspense } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ArrowLeft, Pause, Play, XCircle, Clock, CheckCircle2, AlertCircle, Send, TerminalSquare,
  FolderOpen, ListTodo, Save,
  Terminal, Sparkles, Brain, Repeat, GitBranch, RefreshCw, ShieldCheck, Layers,
  MessageSquare, Route, RotateCcw, Wrench, Plug,
} from 'lucide-react';
import { Card, CardHeader, Button, Spinner, Badge, Input } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { GateStatusBadge } from '../components/shared/GateStatusBadge';
import { GateStatusBanner } from '../components/shared/GateStatusBanner';
import { ErrorState } from '../components/shared/ErrorState';
import { LogViewer } from '../components/shared/LogViewer';
import { ApprovalDialog } from '../components/shared/ApprovalDialog';
import { HumanInputDialog } from '../components/shared/HumanInputDialog';
import { AgenticStepCard } from '../components/shared/AgenticStepCard';
import { ForEachStepCard } from '../components/shared/ForEachStepCard';
import { PathLinkedOutput } from '../components/shared/PathLinkedOutput';
import { InsightsPanel } from '../components/shared/InsightsPanel';
import { ConversionSuggestion } from '../components/shared/ConversionSuggestion';
import {
  useWorkflow, useWorkflowRuns,
  usePauseExecution, useResumeExecution, useCancelExecution, useRunCommand, useSubmitApproval,
  useRetryExecution, useApproveTodos, useResumeFromStep, useUpdateRunNotes,
} from '../hooks/useWorkflows';
import { useCreateTodo } from '../hooks/useTodos';
import { useExecutionWebSocket } from '../hooks/useWebSocket';
import { useRateRun } from '../hooks/useLearning';
import { useToast } from '../hooks/useToast';
import { useT } from '../i18n';
import { formatDuration } from '../utils/formatTime';
import { api, openPath } from '../api/client';
import type { WorkflowRun, StepResult, PendingHumanInput, PendingTodoItem } from '../types';

const TerminalPanel = lazy(() => import('../components/shared/TerminalPanel').then(m => ({ default: m.TerminalPanel })));

const BLOCK_TYPE_ICONS: Record<string, React.ReactNode> = {
  shell: <Terminal className="w-3 h-3" />,
  simple: <Sparkles className="w-3 h-3" />,
  agentic: <Brain className="w-3 h-3" />,
  foreach: <Repeat className="w-3 h-3" />,
  conditional: <GitBranch className="w-3 h-3" />,
  while: <RefreshCw className="w-3 h-3" />,
  approval: <ShieldCheck className="w-3 h-3" />,
  'sub-workflow': <Layers className="w-3 h-3" />,
  'human-input': <MessageSquare className="w-3 h-3" />,
  switch: <Route className="w-3 h-3" />,
  retry: <RotateCcw className="w-3 h-3" />,
  'skill-capability': <Wrench className="w-3 h-3" />,
  integration: <Plug className="w-3 h-3" />,
};

function stepMetadataSummary(step: StepResult) {
  const metadata = step.metadata ?? {};
  const provider = typeof metadata.provider === 'string' ? metadata.provider : null;
  const model = typeof metadata.model === 'string' ? metadata.model : null;
  const requestedModel = typeof metadata.requestedModel === 'string' ? metadata.requestedModel : null;
  const aiSystemPrompt = typeof metadata.aiSystemPrompt === 'string' ? metadata.aiSystemPrompt : null;
  const aiRequest = metadata.aiRequest && typeof metadata.aiRequest === 'object' && !Array.isArray(metadata.aiRequest)
    ? (metadata.aiRequest as Record<string, unknown>)
    : null;

  return {
    provider,
    model,
    requestedModel,
    showRequestedModel: requestedModel != null && requestedModel !== model,
    aiSystemPrompt,
    aiRequest,
  };
}

function StepCard({ step }: { step: StepResult }) {
  const t = useT();
  const [inputExpanded, setInputExpanded] = useState(false);
  const [systemPromptExpanded, setSystemPromptExpanded] = useState(false);
  const { provider, model, requestedModel, showRequestedModel, aiSystemPrompt, aiRequest } = stepMetadataSummary(step);
  const systemPromptLineCount = aiSystemPrompt ? aiSystemPrompt.split('\n').length : 0;
  const aiRequestEntries = aiRequest
    ? Object.entries(aiRequest).filter(([, v]) => v !== null && v !== undefined && v !== '')
    : [];

  return (
    <div className="flex gap-3 py-3">
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
        <div className="flex items-center gap-2 mb-1">
          <span className="text-xs font-mono text-text-tertiary">Step {step.stepIndex + 1}</span>
          <Badge variant={step.status === 'COMPLETED' ? 'success' : step.status === 'FAILED' ? 'danger' : 'default'}>
            <span className="inline-flex items-center gap-1">
              {BLOCK_TYPE_ICONS[step.blockType]}
              {step.blockType}
            </span>
          </Badge>
          {step.durationMs != null && (
            <span className="text-xs text-text-tertiary flex items-center gap-1">
              <Clock className="w-3 h-3" />
              {formatDuration(step.durationMs)}
            </span>
          )}
        </div>
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
        {step.input && !inputExpanded && (
          <p
            className="text-xs text-text-secondary font-mono mb-1 truncate cursor-pointer hover:text-text transition-colors"
            onClick={() => setInputExpanded(true)}
            title="Klicken zum Aufklappen"
          >{step.input}</p>
        )}
        {step.input && inputExpanded && (
          <div className="mb-2 space-y-2">
            <div>
              <button
                type="button"
                onClick={() => setInputExpanded(false)}
                className="text-[11px] uppercase tracking-wide text-text-tertiary hover:text-text mb-1"
                title="Klicken zum Einklappen"
              >
                {t.workflows.execution.stepInput.command}
              </button>
              <pre className="text-xs text-text-secondary font-mono bg-surface rounded-lg p-3 overflow-x-auto max-h-60 whitespace-pre-wrap break-all">{step.input}</pre>
            </div>

            {aiSystemPrompt && (
              <div>
                <button
                  type="button"
                  onClick={() => setSystemPromptExpanded(e => !e)}
                  className="text-[11px] uppercase tracking-wide text-text-tertiary hover:text-text mb-1 flex items-center gap-2"
                >
                  <span>{t.workflows.execution.stepInput.systemPrompt}</span>
                  <span className="text-text-tertiary normal-case tracking-normal">
                    ({t.workflows.execution.stepInput.lineCount.replace('{count}', String(systemPromptLineCount))})
                  </span>
                  <span className="text-text-tertiary normal-case tracking-normal">
                    · {systemPromptExpanded
                      ? t.workflows.execution.stepInput.hideSystemPrompt
                      : t.workflows.execution.stepInput.showSystemPrompt}
                  </span>
                </button>
                {systemPromptExpanded && (
                  <pre className="text-xs text-text-secondary font-mono bg-surface rounded-lg p-3 overflow-auto max-h-80 whitespace-pre-wrap break-all">{aiSystemPrompt}</pre>
                )}
              </div>
            )}

            {aiRequestEntries.length > 0 && (
              <div>
                <div className="text-[11px] uppercase tracking-wide text-text-tertiary mb-1">
                  {t.workflows.execution.stepInput.request}
                </div>
                <ul className="text-xs font-mono bg-surface rounded-lg p-3 space-y-0.5">
                  {aiRequestEntries.map(([k, v]) => (
                    <li key={k} className="flex gap-2">
                      <span className="text-text-tertiary">{k}</span>
                      <span className="text-text">{String(v)}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
        {step.output && (
          <pre className="text-xs text-text font-mono bg-surface rounded-lg p-3 overflow-x-auto max-h-40 whitespace-pre-wrap">
            <PathLinkedOutput text={step.output} />
          </pre>
        )}
        {step.error && (
          <p className="text-xs text-danger mt-1">{step.error}</p>
        )}
      </div>
    </div>
  );
}

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

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Steps */}
        <Card padding={false}>
          <div className="p-5 pb-0">
            <CardHeader title={`${t.workflows.execution.steps} (${run.steps.length})`} />
          </div>
          <div className="px-5 pb-5 divide-y divide-border">
            {run.steps.map((step) =>
              step.blockType === 'agentic' ? (
                <AgenticStepCard key={step.id} step={step} agenticState={agenticStates.get(step.stepIndex)} />
              ) : step.blockType === 'foreach' ? (
                <ForEachStepCard key={step.id} step={step} forEachState={forEachStates.get(step.stepIndex)} />
              ) : (
                <StepCard key={step.id} step={step} />
              ),
            )}
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
