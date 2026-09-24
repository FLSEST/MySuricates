import { useNavigate } from 'react-router-dom';
import { ChevronRight, ChevronDown, ExternalLink } from 'lucide-react';
import { Card, Badge } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { formatRelativeTime } from '../../utils/formatTime';
import { computeIterationDiff, type IterationEntry } from '../../utils/iterationDiff';
import type { Wish } from '../../types';
import { IterationDiffSection } from './IterationDiffSection';

/**
 * Collapsible history of a wish's iterations: plan, workflows, run, evaluation and what changed
 * from the previous iteration. The open state belongs to the caller, so it outlives a switch
 * between wishes.
 */
export function WishIterationHistory({ wish, open, onToggle }: {
  wish: Wish;
  open: boolean;
  onToggle: () => void;
}) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const navigate = useNavigate();

  const runHref = (runId: string, workflowId?: string) => wish.generatedChainId
    ? `/chains/${wish.generatedChainId}/run/${runId}`
    : workflowId ? `/workflows/${workflowId}/run/${runId}` : `/runs/${runId}`;

  return (
    <Card>
      <div className="p-4">
        <button
          type="button"
          className="flex items-center gap-2 w-full text-left"
          onClick={onToggle}
        >
          {open ? <ChevronDown className="w-4 h-4 text-text-tertiary" /> : <ChevronRight className="w-4 h-4 text-text-tertiary" />}
          <h3 className="text-sm font-semibold text-text-secondary uppercase tracking-wide">
            {t.wishes.iterationHistory} ({wish.iterationHistory.length})
          </h3>
        </button>
        {open && (
          <div className="mt-3 space-y-3">
            {wish.iterationHistory.map((entry, idx) => {
              const iteration = entry.iteration as number ?? idx + 1;
              const timestamp = entry.timestamp as string;
              const decomposition = entry.decomposition as Record<string, unknown> | undefined;
              const evaluation = entry.evaluation as Record<string, unknown> | undefined;
              const runId = entry.runId as string | undefined;
              const workflowIds = entry.workflowIds as string[] | undefined;
              const prevEntry = idx > 0 ? (wish.iterationHistory[idx - 1] as IterationEntry) : null;
              const diffs = computeIterationDiff(prevEntry, entry as IterationEntry);
              const prevIterationNumber = prevEntry
                ? ((prevEntry.iteration as number | undefined) ?? idx)
                : undefined;

              return (
                <div key={idx} className="rounded-lg border border-border bg-surface-raised p-3 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium text-text-primary">
                      {t.wishes.iteration} {iteration}
                    </span>
                    {timestamp && (
                      <span className="text-xs text-text-tertiary">
                        {formatRelativeTime(timestamp, locale)}
                      </span>
                    )}
                  </div>

                  {decomposition && (
                    <div className="space-y-1">
                      {typeof decomposition.analysis === 'string' && (
                        <p className="text-xs text-text-secondary">{decomposition.analysis}</p>
                      )}
                      {typeof decomposition.strategy === 'string' && (
                        <p className="text-xs text-text-tertiary">{decomposition.strategy}</p>
                      )}
                    </div>
                  )}

                  {workflowIds && workflowIds.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {workflowIds.map(wfId => (
                        <span
                          key={wfId}
                          className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs bg-surface-base text-accent cursor-pointer hover:underline"
                          onClick={() => navigate(`/workflows/${wfId}`)}
                        >
                          {wfId.slice(0, 8)}...
                          <ExternalLink className="w-3 h-3" />
                        </span>
                      ))}
                    </div>
                  )}

                  {runId && (
                    <div
                      className="text-xs text-accent cursor-pointer hover:underline flex items-center gap-1"
                      onClick={() => navigate(runHref(runId, workflowIds?.[0]))}
                    >
                      Run: {runId.slice(0, 8)}...
                      <ExternalLink className="w-3 h-3" />
                    </div>
                  )}

                  {evaluation && (
                    <div className="rounded bg-surface-base p-2 space-y-1">
                      <div className="flex items-center gap-2">
                        <Badge variant={evaluation.satisfied ? 'success' : 'danger'}>
                          {evaluation.satisfied ? t.wishes.historySatisfied : t.wishes.historyNotSatisfied}
                        </Badge>
                      </div>
                      {typeof evaluation.summary === 'string' && (
                        <p className="text-xs text-text-secondary">{evaluation.summary}</p>
                      )}
                      {Array.isArray(evaluation.issues) && evaluation.issues.length > 0 && (
                        <ul className="text-xs text-text-tertiary list-disc ml-4">
                          {(evaluation.issues as string[]).map((issue, i) => (
                            <li key={i}>{issue}</li>
                          ))}
                        </ul>
                      )}
                    </div>
                  )}

                  {prevEntry && (
                    <IterationDiffSection
                      diffs={diffs}
                      prevIteration={prevIterationNumber ?? idx}
                    />
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </Card>
  );
}
