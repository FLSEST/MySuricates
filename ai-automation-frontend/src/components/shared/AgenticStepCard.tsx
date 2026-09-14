import { useState } from 'react';
import {
  Brain, Terminal, CheckCircle2, AlertCircle, ChevronDown, ChevronRight,
  Target, Loader2, XCircle, ShieldQuestion,
} from 'lucide-react';
import { Badge } from '../ui';
import { PathLinkedOutput } from './PathLinkedOutput';
import { useT } from '../../i18n';
/* Badge used in IterationCard, custom span for agentic badge in main card */
import type { StepResult, AgenticState, AgenticIteration } from '../../types';
import { StepCardFrame, StepCardHeader } from '../workflow/StepCardChrome';

function IterationCard({ iter }: { iter: AgenticIteration }) {
  const [expanded, setExpanded] = useState(false);
  const t = useT();

  const phaseIcon = {
    reasoning: <Brain className="w-4 h-4 text-purple-500 animate-pulse" />,
    acting: <Terminal className="w-4 h-4 text-blue-500 animate-pulse" />,
    acted: iter.exitCode === 0
      ? <CheckCircle2 className="w-4 h-4 text-success" />
      : <AlertCircle className="w-4 h-4 text-danger" />,
    approval: <ShieldQuestion className="w-4 h-4 text-warning" />,
    completed: <CheckCircle2 className="w-4 h-4 text-success" />,
    error: <XCircle className="w-4 h-4 text-danger" />,
  };

  const phaseBadge = {
    reasoning: <Badge variant="default">{t.workflows.execution.agentic.reasoning}</Badge>,
    acting: <Badge variant="warning">{t.workflows.execution.agentic.acting}</Badge>,
    acted: iter.exitCode === 0
      ? <Badge variant="success">{t.workflows.execution.agentic.result}</Badge>
      : <Badge variant="danger">{t.workflows.execution.agentic.result}</Badge>,
    approval: <Badge variant="warning">Approval</Badge>,
    completed: <Badge variant="success">{t.workflows.execution.agentic.goalAchieved}</Badge>,
    error: <Badge variant="danger">{t.common.error}</Badge>,
  };

  return (
    <div className="border border-border rounded-lg overflow-hidden">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center gap-3 px-3 py-2 hover:bg-surface-raised transition-colors text-left"
      >
        {expanded ? <ChevronDown className="w-4 h-4 text-text-tertiary" /> : <ChevronRight className="w-4 h-4 text-text-tertiary" />}
        {phaseIcon[iter.phase]}
        <span className="text-xs font-mono text-text-tertiary">
          #{iter.iteration}
        </span>
        {phaseBadge[iter.phase]}
        {iter.exitCode != null && (
          <span className="text-xs text-text-tertiary ml-auto">
            {t.workflows.execution.agentic.exitCode}: {iter.exitCode}
          </span>
        )}
      </button>

      {expanded && (
        <div className="px-3 pb-3 space-y-2 border-t border-border bg-surface">
          {iter.reasoning && (
            <div>
              <p className="text-xs font-medium text-text-secondary mb-1 flex items-center gap-1">
                <Brain className="w-3 h-3" />
                {t.workflows.execution.agentic.reasoning}
              </p>
              <pre className="text-xs font-mono text-text bg-background rounded-sm p-2 overflow-x-auto max-h-32 whitespace-pre-wrap">
                {iter.reasoning}
              </pre>
            </div>
          )}

          {iter.action && (
            <div>
              <p className="text-xs font-medium text-text-secondary mb-1 flex items-center gap-1">
                <Terminal className="w-3 h-3" />
                {t.workflows.execution.agentic.action}
              </p>
              <pre className="text-xs font-mono text-text bg-background rounded-sm p-2 overflow-x-auto max-h-20 whitespace-pre-wrap">
                {iter.action}
              </pre>
            </div>
          )}

          {iter.output && (
            <div>
              <p className="text-xs font-medium text-text-secondary mb-1">
                {t.workflows.execution.agentic.result}
              </p>
              <pre className="text-xs font-mono text-text bg-background rounded-sm p-2 overflow-x-auto max-h-40 whitespace-pre-wrap">
                <PathLinkedOutput text={iter.output} />
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

interface AgenticStepCardProps {
  step: StepResult;
  agenticState?: AgenticState;
}

export function AgenticStepCard({ step, agenticState }: AgenticStepCardProps) {
  const [expanded, setExpanded] = useState(true);
  const t = useT();

  const isRunning = step.status === 'RUNNING';
  const goalAchieved = agenticState?.goalAchieved;
  const iterations = agenticState?.iterations ?? [];
  const currentIteration = agenticState?.currentIteration ?? 0;
  const maxIterations = agenticState?.maxIterations ?? 0;

  return (
    <StepCardFrame step={step}>
      <div className="pt-0.5">
        {goalAchieved === true ? (
          <CheckCircle2 className="w-5 h-5 text-success" />
        ) : goalAchieved === false ? (
          <AlertCircle className="w-5 h-5 text-danger" />
        ) : isRunning ? (
          <Loader2 className="w-5 h-5 text-accent animate-spin" />
        ) : step.status === 'COMPLETED' ? (
          <CheckCircle2 className="w-5 h-5 text-success" />
        ) : step.status === 'FAILED' ? (
          <AlertCircle className="w-5 h-5 text-danger" />
        ) : (
          <div className="w-5 h-5 rounded-full border-2 border-border" />
        )}
      </div>

      <div className="flex-1 min-w-0">
        {/* Header */}
        <StepCardHeader
          step={step}
          className="mb-2"
          badge={
            <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-medium border bg-purple-100 text-purple-700 border-purple-200 dark:bg-purple-900/30 dark:text-purple-300 dark:border-purple-700/30">
              <Brain className="w-3 h-3" />
              {t.workflows.execution.agentic.title}
            </span>
          }
        />

        {/* Goal */}
        {agenticState?.goal && (
          <div className="flex items-center gap-2 mb-2 text-sm">
            <Target className="w-4 h-4 text-accent" />
            <span className="text-text">{agenticState.goal}</span>
          </div>
        )}

        {/* Progress bar */}
        {maxIterations > 0 && (
          <div className="mb-3">
            <div className="flex items-center justify-between text-xs text-text-tertiary mb-1">
              <span>
                {t.workflows.execution.agentic.iteration} {currentIteration} / {maxIterations}
              </span>
              {goalAchieved === true && (
                <span className="text-success font-medium">
                  {t.workflows.execution.agentic.goalAchieved}
                </span>
              )}
              {goalAchieved === false && (
                <span className="text-danger font-medium">
                  {t.workflows.execution.agentic.goalNotAchieved}
                </span>
              )}
              {goalAchieved === null && isRunning && (
                <span className="text-accent font-medium">
                  {t.workflows.execution.agentic.inProgress}
                </span>
              )}
            </div>
            <div className="w-full bg-surface rounded-full h-1.5">
              <div
                className={`h-1.5 rounded-full transition-all duration-500 ${
                  goalAchieved === true ? 'bg-success'
                  : goalAchieved === false ? 'bg-danger'
                  : 'bg-accent'
                }`}
                style={{ width: `${maxIterations > 0 ? (currentIteration / maxIterations) * 100 : 0}%` }}
              />
            </div>
          </div>
        )}

        {/* Final summary */}
        {agenticState?.finalSummary && (
          <div className={`text-sm p-2 rounded-lg mb-3 ${
            goalAchieved ? 'bg-success-subtle text-success' : 'bg-danger-subtle text-danger'
          }`}>
            {agenticState.finalSummary}
          </div>
        )}

        {/* Iterations */}
        {iterations.length > 0 && (
          <div>
            <button
              onClick={() => setExpanded(!expanded)}
              className="flex items-center gap-1 text-xs text-text-secondary hover:text-text mb-2"
            >
              {expanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
              {iterations.length} {iterations.length !== 1 ? t.workflows.execution.agentic.iterations : t.workflows.execution.agentic.iteration}
            </button>

            {expanded && (
              <div className="space-y-2">
                {iterations
                  .sort((a, b) => a.iteration - b.iteration)
                  .map((iter) => (
                    <IterationCard key={iter.iteration} iter={iter} />
                  ))}
              </div>
            )}
          </div>
        )}

        {/* Fallback: show step output if no agentic state */}
        {!agenticState && step.output && (
          <pre className="text-xs text-text font-mono bg-surface rounded-lg p-3 overflow-x-auto max-h-40 whitespace-pre-wrap">
            <PathLinkedOutput text={step.output} />
          </pre>
        )}
      </div>
    </StepCardFrame>
  );
}
