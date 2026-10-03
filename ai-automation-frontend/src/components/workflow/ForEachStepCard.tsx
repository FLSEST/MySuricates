import { useState } from 'react';
import { CheckCircle2, AlertCircle, ChevronDown, ChevronRight, List } from 'lucide-react';
import { Badge } from '../ui';
import { useT } from '../../i18n';
import { PathLinkedOutput } from '../shared/PathLinkedOutput';
import type { StepResult } from '../../types';
import type { ForEachState, ForEachItemState } from '../../hooks/useWebSocket';
import { persistedForEachItems, persistedTotalItems, stepMetadataSummary, stepWaitsForPerson } from './stepMetadata';
import { StepCardFrame, StepCardHeader, StepWaitingIcon } from './StepCardChrome';

interface ForEachItemRowProps {
  item: ForEachItemState;
}

function ForEachItemRow({ item }: ForEachItemRowProps) {
  const [expanded, setExpanded] = useState(false);

  return (
    <div className="border border-border rounded-lg overflow-hidden">
      <button
        className="w-full flex items-center gap-2 p-2 hover:bg-surface-raised transition-colors text-left"
        onClick={() => setExpanded(!expanded)}
      >
        {item.status === 'running' ? (
          <div className="w-4 h-4 rounded-full border-2 border-accent border-t-transparent animate-spin shrink-0" />
        ) : item.status === 'completed' ? (
          <CheckCircle2 className="w-4 h-4 text-success shrink-0" />
        ) : (
          <AlertCircle className="w-4 h-4 text-danger shrink-0" />
        )}
        <span className="text-xs font-mono text-text flex-1 truncate">{item.item}</span>
        <Badge variant={item.status === 'completed' ? 'success' : item.status === 'failed' ? 'danger' : 'default'}>
          {item.status}
        </Badge>
        {item.output && (expanded ? <ChevronDown className="w-3 h-3 text-text-tertiary shrink-0" /> : <ChevronRight className="w-3 h-3 text-text-tertiary shrink-0" />)}
      </button>
      {expanded && item.output && (
        <div className="border-t border-border p-2 bg-surface">
          <pre className="text-xs text-text font-mono whitespace-pre-wrap max-h-32 overflow-y-auto">
            <PathLinkedOutput text={item.output} />
          </pre>
        </div>
      )}
    </div>
  );
}

interface ForEachStepCardProps {
  step: StepResult;
  forEachState?: ForEachState;
  /** false once the run has ended — a step left waiting then no longer counts as running. */
  runActive?: boolean;
}

export function ForEachStepCard({ step, forEachState, runActive = true }: ForEachStepCardProps) {
  const t = useT();
  const [expanded, setExpanded] = useState(false);

  // Live WebSocket state while running; the persisted step metadata after a reload
  const items = forEachState?.items ?? persistedForEachItems(step);
  const completedItems = forEachState?.completedItems ?? items.filter((i) => i.status !== 'running').length;
  const totalItems = forEachState?.totalItems ?? persistedTotalItems(step) ?? items.length;
  const hasItemData = forEachState != null || items.length > 0;
  const progress = totalItems > 0 ? Math.round((completedItems / totalItems) * 100) : null;
  // Waiting for a person (TODO 4eea7a87) is part of the run, as in AgenticStepCard
  const isRunning = step.status === 'RUNNING' || stepWaitsForPerson(step, runActive);
  const { provider, model, requestedModel, showRequestedModel } = stepMetadataSummary(step);

  return (
    <StepCardFrame step={step}>
      <div className="pt-0.5">
        {step.status === 'COMPLETED' ? (
          <CheckCircle2 className="w-5 h-5 text-success" />
        ) : step.status === 'FAILED' ? (
          <AlertCircle className="w-5 h-5 text-danger" />
        ) : step.status === 'WAITING_APPROVAL' ? (
          <StepWaitingIcon runActive={runActive} />
        ) : isRunning ? (
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

        {step.input && (
          <p className="text-xs text-text-secondary font-mono mb-2 truncate">{step.input}</p>
        )}

        {/* Progress bar — shown during execution or when we have iteration data */}
        {(hasItemData || isRunning) && (
          <div className="mb-2">
            <div className="flex items-center justify-between mb-1">
              <span className="text-xs text-text-secondary flex items-center gap-1">
                <List className="w-3 h-3" />
                {t.workflows.foreachItems.replace('{done}', String(completedItems)).replace('{total}', totalItems > 0 ? String(totalItems) : '?')}
              </span>
              {progress !== null && (
                <span className="text-xs font-medium text-accent">{progress}%</span>
              )}
            </div>
            <div className="h-1.5 bg-surface rounded-full overflow-hidden">
              <div
                className="h-full bg-accent rounded-full transition-all duration-300"
                style={{ width: progress !== null ? `${progress}%` : '0%' }}
              />
            </div>
          </div>
        )}

        {/* Iteration list (live or persisted) — collapsible */}
        {items.length > 0 && (
          <div>
            <button
              className="flex items-center gap-1 text-xs text-text-tertiary hover:text-text transition-colors mb-1"
              onClick={() => setExpanded(!expanded)}
            >
              {expanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
              {expanded ? 'Hide items' : `Show ${items.length} items`}
            </button>
            {expanded && (
              <div className="space-y-1.5 max-h-64 overflow-y-auto">
                {items
                  .slice()
                  .sort((a, b) => a.itemIndex - b.itemIndex)
                  .map((item) => (
                    <ForEachItemRow key={item.itemIndex} item={item} />
                  ))}
              </div>
            )}
          </div>
        )}

        {/* Final output — shown after completion when no live state */}
        {!forEachState && step.output && (
          <pre className="text-xs text-text font-mono bg-surface rounded-lg p-3 overflow-x-auto max-h-40 whitespace-pre-wrap">
            <PathLinkedOutput text={step.output} />
          </pre>
        )}
      </div>
    </StepCardFrame>
  );
}
