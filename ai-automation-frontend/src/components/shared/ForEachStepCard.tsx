import { useState } from 'react';
import { CheckCircle2, AlertCircle, Clock, ChevronDown, ChevronRight, List } from 'lucide-react';
import { Badge } from '../ui';
import { PathLinkedOutput } from './PathLinkedOutput';
import { formatDuration } from '../../utils/formatTime';
import type { StepResult } from '../../types';
import type { ForEachState, ForEachItemState } from '../../hooks/useWebSocket';

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
}

export function ForEachStepCard({ step, forEachState }: ForEachStepCardProps) {
  const [expanded, setExpanded] = useState(false);

  const completedItems = forEachState?.completedItems ?? 0;
  const totalItems = forEachState?.totalItems ?? 0;
  const progress = totalItems > 0 ? Math.round((completedItems / totalItems) * 100) : null;
  const isRunning = step.status === 'RUNNING';

  return (
    <div className="flex gap-3 py-3">
      <div className="pt-0.5">
        {step.status === 'COMPLETED' ? (
          <CheckCircle2 className="w-5 h-5 text-success" />
        ) : step.status === 'FAILED' ? (
          <AlertCircle className="w-5 h-5 text-danger" />
        ) : isRunning ? (
          <div className="w-5 h-5 rounded-full border-2 border-accent border-t-transparent animate-spin" />
        ) : (
          <div className="w-5 h-5 rounded-full border-2 border-border" />
        )}
      </div>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 mb-1">
          <span className="text-xs font-mono text-text-tertiary">Step {step.stepIndex + 1}</span>
          <Badge variant={step.status === 'COMPLETED' ? 'success' : step.status === 'FAILED' ? 'danger' : 'default'}>
            forEach
          </Badge>
          {step.durationMs != null && (
            <span className="text-xs text-text-tertiary flex items-center gap-1">
              <Clock className="w-3 h-3" />
              {formatDuration(step.durationMs)}
            </span>
          )}
        </div>

        {step.input && (
          <p className="text-xs text-text-secondary font-mono mb-2 truncate">{step.input}</p>
        )}

        {/* Progress bar — shown during execution or when we have iteration data */}
        {(forEachState || isRunning) && (
          <div className="mb-2">
            <div className="flex items-center justify-between mb-1">
              <span className="text-xs text-text-secondary flex items-center gap-1">
                <List className="w-3 h-3" />
                {completedItems}/{totalItems > 0 ? totalItems : '?'} items
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

        {/* Live iteration list — collapsible */}
        {forEachState && forEachState.items.length > 0 && (
          <div>
            <button
              className="flex items-center gap-1 text-xs text-text-tertiary hover:text-text transition-colors mb-1"
              onClick={() => setExpanded(!expanded)}
            >
              {expanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
              {expanded ? 'Hide items' : `Show ${forEachState.items.length} items`}
            </button>
            {expanded && (
              <div className="space-y-1.5 max-h-64 overflow-y-auto">
                {forEachState.items
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
    </div>
  );
}
