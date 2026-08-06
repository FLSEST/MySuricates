import { useState } from 'react';
import {
  Clock, CheckCircle2, AlertCircle,
  Terminal, Sparkles, Brain, Repeat, GitBranch, RefreshCw, ShieldCheck, Layers,
  MessageSquare, Route, RotateCcw, Wrench, Plug,
} from 'lucide-react';
import { Badge } from '../ui';
import { PathLinkedOutput } from '../shared/PathLinkedOutput';
import { useT } from '../../i18n';
import { formatDuration } from '../../utils/formatTime';
import type { StepResult } from '../../types';

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

export function ExecutionStepCard({ step }: { step: StepResult }) {
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
