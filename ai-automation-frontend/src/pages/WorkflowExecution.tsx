import { useState, useEffect, lazy, Suspense } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ArrowLeft, Pause, Play, XCircle, Clock, CheckCircle2, AlertCircle, Send, TerminalSquare,
} from 'lucide-react';
import { Card, CardHeader, Button, Spinner, Badge, Input } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { ErrorState } from '../components/shared/ErrorState';
import { LogViewer } from '../components/shared/LogViewer';
import { ApprovalDialog } from '../components/shared/ApprovalDialog';
import { HumanInputDialog } from '../components/shared/HumanInputDialog';
import { AgenticStepCard } from '../components/shared/AgenticStepCard';
import { InsightsPanel } from '../components/shared/InsightsPanel';
import { ConversionSuggestion } from '../components/shared/ConversionSuggestion';
import {
  useWorkflow, useWorkflowRuns,
  usePauseExecution, useResumeExecution, useCancelExecution, useRunCommand, useSubmitApproval,
} from '../hooks/useWorkflows';
import { useExecutionWebSocket } from '../hooks/useWebSocket';
import { useRateRun } from '../hooks/useLearning';
import { useT } from '../i18n';
import { formatDuration } from '../utils/formatTime';
import { api } from '../api/client';
import type { WorkflowRun, StepResult, PendingHumanInput } from '../types';

const TerminalPanel = lazy(() => import('../components/shared/TerminalPanel').then(m => ({ default: m.TerminalPanel })));

function StepCard({ step }: { step: StepResult }) {
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
            {step.blockType}
          </Badge>
          {step.durationMs != null && (
            <span className="text-xs text-text-tertiary flex items-center gap-1">
              <Clock className="w-3 h-3" />
              {formatDuration(step.durationMs)}
            </span>
          )}
        </div>
        {step.input && (
          <p className="text-xs text-text-secondary font-mono mb-1 truncate">{step.input}</p>
        )}
        {step.output && (
          <pre className="text-xs text-text font-mono bg-surface rounded-lg p-3 overflow-x-auto max-h-40 whitespace-pre-wrap">
            {step.output}
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
  const { logs, connected, pendingApproval, pendingHumanInput, agenticStates } = useExecutionWebSocket(runId!, id);
  const rateRun = useRateRun();
  const pauseExecution = usePauseExecution();
  const resumeExecution = useResumeExecution();
  const cancelExecution = useCancelExecution();
  const runCommand = useRunCommand();
  const submitApproval = useSubmitApproval();

  const [command, setCommand] = useState('');
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

  const activeApproval = pendingApproval ?? recoveredApproval;
  const activeHumanInput = pendingHumanInput ?? recoveredHumanInput;

  const run: WorkflowRun | undefined = runs?.content?.find((r) => r.id === runId);
  const isRunning = run?.status === 'RUNNING' || run?.status === 'PAUSED';

  const handleRate = (rating: number) => {
    rateRun.mutate({ runId: runId!, rating });
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
          onClick={() => navigate(`/workflows/${id}`)}
          className="p-2 rounded-lg hover:bg-surface-raised text-text-secondary hover:text-text transition-colors"
          aria-label={t.common.back}
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div className="flex-1">
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold text-text">{t.workflows.execution.title}</h1>
            <StatusBadge status={run.status} />
            {connected && isRunning && (
              <span className="flex items-center gap-1 text-xs text-success">
                <span className="w-2 h-2 rounded-full bg-success animate-pulse" />
                {t.common.live}
              </span>
            )}
          </div>
          <p className="text-sm text-text-secondary mt-0.5">
            {workflow?.name} &middot; {new Date(run.startedAt).toLocaleString()}
          </p>
        </div>
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
          {Object.entries(run.inputContext).map(([key, value]) => (
            <span
              key={key}
              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-surface-raised text-xs font-mono"
            >
              <span className="text-text-secondary">{key}</span>
              <span className="text-text-tertiary">=</span>
              <span className="text-text">{String(value)}</span>
            </span>
          ))}
        </div>
      )}

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
