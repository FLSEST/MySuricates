import { useState } from 'react';
import { CheckCircle2, AlertCircle } from 'lucide-react';
import { PathLinkedOutput } from '../shared/PathLinkedOutput';
import { useT } from '../../i18n';
import type { StepResult } from '../../types';
import { stepMetadataSummary, wrapperFacts, formatMetadataValue } from './stepMetadata';
import { StepCardFrame, StepCardHeader } from './StepCardChrome';
import { CompressionPanel } from './CompressionPanel';

export function ExecutionStepCard({ step }: { step: StepResult }) {
  const t = useT();
  const [inputExpanded, setInputExpanded] = useState(false);
  const [systemPromptExpanded, setSystemPromptExpanded] = useState(false);
  const { provider, model, requestedModel, showRequestedModel, aiSystemPrompt, aiRequest } = stepMetadataSummary(step);
  const facts = wrapperFacts(step);
  const systemPromptLineCount = aiSystemPrompt ? aiSystemPrompt.split('\n').length : 0;
  const aiRequestEntries = aiRequest
    ? Object.entries(aiRequest).filter(([, v]) => v !== null && v !== undefined && v !== '')
    : [];

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
        <CompressionPanel step={step} />
        {(provider || model || showRequestedModel || facts.length > 0) && (
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
            {facts.map(([key, value]) => (
              <span key={key} className="px-2 py-0.5 rounded-full bg-surface-raised text-[11px] font-mono text-text-tertiary">
                {key}={value}
              </span>
            ))}
          </div>
        )}
        {step.input && !inputExpanded && (
          <p
            className="text-xs text-text-secondary font-mono mb-1 truncate cursor-pointer hover:text-text transition-colors"
            onClick={() => setInputExpanded(true)}
            title={t.workflows.execution.stepInput.clickToExpand}
          >{step.input}</p>
        )}
        {step.input && inputExpanded && (
          <div className="mb-2 space-y-2">
            <div>
              <button
                type="button"
                onClick={() => setInputExpanded(false)}
                className="text-[11px] uppercase tracking-wide text-text-tertiary hover:text-text mb-1"
                title={t.workflows.execution.stepInput.clickToCollapse}
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
                      <span className="text-text">{formatMetadataValue(v)}</span>
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
    </StepCardFrame>
  );
}
