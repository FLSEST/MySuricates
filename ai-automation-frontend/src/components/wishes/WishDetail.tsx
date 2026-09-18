import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { XCircle, Trash2, ChevronRight, ChevronDown, ExternalLink, RefreshCw, RotateCcw } from 'lucide-react';
import { Card, Button, Spinner, Badge } from '../ui';
import { useWish, useRespondToWish } from '../../hooks/useWishes';
import { useT } from '../../i18n';
import { formatRelativeTime } from '../../utils/formatTime';
import { computeIterationDiff, type IterationEntry } from '../../utils/iterationDiff';
import type { WishStatus } from '../../types';
import { ApprovalPanel } from './ApprovalPanel';
import { ClarificationPanel } from './ClarificationPanel';
import { DecomposeApprovalPanel } from './DecomposeApprovalPanel';
import { ExecutionLogsPanel } from './ExecutionLogsPanel';
import { IterationDiffSection } from './IterationDiffSection';
import { CrystallizeButton } from './CrystallizeButton';
import { ExecutionStepCard } from '../workflow/ExecutionStepCard';
import { StepAnchorScope } from '../workflow/stepAnchors';
import { PathLinkedOutput } from '../shared/PathLinkedOutput';
import { useWorkflowRuns } from '../../hooks/useWorkflows';

export function WishDetail({ wishId, onCancel, onDelete }: {
  wishId: string;
  onCancel: () => void;
  onDelete: () => void;
}) {
  const t = useT();
  const navigate = useNavigate();
  const { data: wish } = useWish(wishId);
  const { data: generatedRuns } = useWorkflowRuns(wish?.generatedWorkflowIds?.[0] ?? '');
  const respond = useRespondToWish();
  const [historyOpen, setHistoryOpen] = useState(false);

  const isTerminal = (s: WishStatus) => s === 'COMPLETED' || s === 'FAILED' || s === 'CANCELLED';
  const isPaused = (s: WishStatus) => s === 'AWAITING_APPROVAL' || s === 'AWAITING_CLARIFICATION' || s === 'AWAITING_DECOMPOSE_APPROVAL';
  const runHref = (runId: string, workflowId?: string) => wish?.generatedChainId
    ? `/chains/${wish.generatedChainId}/run/${runId}`
    : workflowId ? `/workflows/${workflowId}/run/${runId}` : `/runs/${runId}`;

  if (!wish) return <Card className="p-8 text-center"><Spinner /></Card>;

  const statusSteps: WishStatus[] = ['RECEIVED', 'DECOMPOSING', 'EXECUTING', 'EVALUATING', 'COMPLETED'];

  // Map AWAITING_* statuses to their timeline position
  const effectiveStatus: WishStatus =
    wish.status === 'AWAITING_CLARIFICATION' ? 'DECOMPOSING' :
    wish.status === 'AWAITING_DECOMPOSE_APPROVAL' ? 'DECOMPOSING' :
    wish.status === 'AWAITING_APPROVAL' ? 'EVALUATING' :
    wish.status;
  const currentIndex = statusSteps.indexOf(effectiveStatus);

  return (
    <div className="space-y-4">
      {/* Header */}
      <Card>
        <div className="p-4">
          <div className="flex items-start justify-between mb-3">
            <div className="flex-1 min-w-0">
              <p className="text-text-primary font-medium">{wish.originalText}</p>
              {wish.projectName && (
                <p className="text-sm text-text-tertiary mt-1">{t.wishes.project}: {wish.projectName}</p>
              )}
              {wish.autoRefine && (
                <div className="mt-1.5">
                  <Badge variant="accent">
                    <RefreshCw className="w-3 h-3 mr-1 inline" />
                    {t.wishes.autoRefine.enabled}
                  </Badge>
                </div>
              )}
            </div>
            <div className="flex items-center gap-2 ml-4">
              {!isTerminal(wish.status) && !isPaused(wish.status) && (
                <Button variant="ghost" size="sm" icon={<XCircle className="w-4 h-4" />} onClick={onCancel}>
                  {t.wishes.cancel}
                </Button>
              )}
              {(wish.status === 'FAILED' || wish.status === 'CANCELLED') && (
                <Button
                  variant="secondary"
                  size="sm"
                  icon={<RotateCcw className="w-4 h-4" />}
                  onClick={() => respond.mutate({ id: wish.id, data: { action: 'RETRY' } })}
                  disabled={respond.isPending}
                >
                  {t.wishes.retry}
                </Button>
              )}
              {isTerminal(wish.status) && (
                <Button variant="ghost" size="sm" icon={<Trash2 className="w-4 h-4" />} onClick={onDelete} title={t.common.delete} />
              )}
            </div>
          </div>

          {/* Status Timeline */}
          <div className="flex items-center gap-1 mt-4">
            {statusSteps.map((step, i) => {
              const isActive = effectiveStatus === step;
              const isPastStep = currentIndex > i || wish.status === 'COMPLETED';
              const isFailed = wish.status === 'FAILED' && i === currentIndex;
              const isAwaitingHere = isActive && isPaused(wish.status);
              return (
                <div key={step} className="flex items-center gap-1 flex-1">
                  <div className={`h-1.5 flex-1 rounded-full ${
                    isFailed ? 'bg-danger' :
                    isAwaitingHere ? 'bg-warning animate-pulse' :
                    isActive ? 'bg-accent animate-pulse' :
                    isPastStep ? 'bg-success' :
                    'bg-border'
                  }`} />
                </div>
              );
            })}
          </div>
          <div className="flex justify-between mt-1">
            {statusSteps.map(step => {
              const label = isPaused(wish.status) && effectiveStatus === step
                ? (t.wishes.status[wish.status.toLowerCase() as keyof typeof t.wishes.status] ?? wish.status)
                : (t.wishes.status[step.toLowerCase() as keyof typeof t.wishes.status] ?? step);
              const isHighlighted = effectiveStatus === step;
              return (
                <span key={step} className={`text-[10px] ${
                  isHighlighted && isPaused(wish.status) ? 'text-warning font-medium' :
                  isHighlighted ? 'text-accent font-medium' :
                  'text-text-tertiary'
                }`}>
                  {label}
                </span>
              );
            })}
          </div>

          {/* Iteration counter */}
          {wish.iterationCount > 0 && (
            <div className="mt-3 flex items-center gap-2">
              <span className="text-sm text-text-secondary">
                {t.wishes.iteration} {wish.iterationCount} / {wish.maxIterations}
              </span>
              {wish.status === 'REFINING' && (
                <Badge variant="accent" dot>{t.wishes.status.refining}</Badge>
              )}
            </div>
          )}
        </div>
      </Card>

      {/* Decompose Approval Panel */}
      {wish.status === 'AWAITING_DECOMPOSE_APPROVAL' && (
        <DecomposeApprovalPanel wish={wish} />
      )}

      {/* Approval Panel */}
      {wish.status === 'AWAITING_APPROVAL' && (
        <ApprovalPanel wish={wish} />
      )}

      {/* Clarification Panel */}
      {wish.status === 'AWAITING_CLARIFICATION' && (
        <ClarificationPanel wish={wish} />
      )}

      {/* Execution Logs */}
      {wish.status === 'EXECUTING' && wish.currentRunId && (
        <ExecutionLogsPanel runId={wish.currentRunId} />
      )}

      {/* Decomposition */}
      {wish.decomposition && Object.keys(wish.decomposition).length > 0 && (
        <Card>
          <div className="p-4">
            <h3 className="text-sm font-semibold text-text-secondary uppercase tracking-wide mb-3">
              {t.wishes.decomposition}
            </h3>
            {typeof wish.decomposition.analysis === 'string' && (
              <p className="text-sm text-text-primary mb-2">{wish.decomposition.analysis}</p>
            )}
            {typeof wish.decomposition.strategy === 'string' && (
              <p className="text-sm text-text-tertiary mb-3">{wish.decomposition.strategy}</p>
            )}
          </div>
        </Card>
      )}

      {/* Generated Workflows */}
      {wish.generatedWorkflowIds.length > 0 && (
        <Card>
          <div className="p-4">
            <h3 className="text-sm font-semibold text-text-secondary uppercase tracking-wide mb-3">
              {t.wishes.generatedWorkflows} ({wish.generatedWorkflowIds.length})
            </h3>
            <div className="space-y-2">
              {wish.generatedWorkflowIds.map((wfId, i) => (
                <div
                  key={wfId}
                  className="flex items-center justify-between p-2 rounded-lg hover:bg-surface-raised cursor-pointer transition-colors"
                  onClick={() => navigate(`/workflows/${wfId}`)}
                >
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-mono text-text-tertiary">{i + 1}</span>
                    <span className="text-sm text-text-primary">
                      {wish.generatedWorkflowNames[i] ?? wfId}
                    </span>
                  </div>
                  <ExternalLink className="w-3.5 h-3.5 text-text-tertiary" />
                </div>
              ))}
            </div>
            {wish.generatedChainId && (
              <div
                className="mt-2 p-2 rounded-lg hover:bg-surface-raised cursor-pointer transition-colors border border-border"
                onClick={() => navigate(`/chains`)}
              >
                <span className="text-sm text-accent flex items-center gap-1">
                  Chain <ChevronRight className="w-3.5 h-3.5" />
                </span>
              </div>
            )}
          </div>
        </Card>
      )}

      {/* Iteration History */}
      {wish.iterationHistory && wish.iterationHistory.length > 0 && (
        <Card>
          <div className="p-4">
            <button
              type="button"
              className="flex items-center gap-2 w-full text-left"
              onClick={() => setHistoryOpen(v => !v)}
            >
              {historyOpen ? <ChevronDown className="w-4 h-4 text-text-tertiary" /> : <ChevronRight className="w-4 h-4 text-text-tertiary" />}
              <h3 className="text-sm font-semibold text-text-secondary uppercase tracking-wide">
                {t.wishes.iterationHistory} ({wish.iterationHistory.length})
              </h3>
            </button>
            {historyOpen && (
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
                            {formatRelativeTime(timestamp)}
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
      )}

      {/* Crystallize Button */}
      {wish.status === 'COMPLETED' && wish.generatedWorkflowIds.length > 0 && (
        <CrystallizeButton wish={wish} />
      )}

      {/* Result */}
      {generatedRuns?.content?.find(run => run.id === wish.currentRunId)?.steps?.length ? (
        <Card>
          <div className="p-4 space-y-2">
            <h3 className="text-sm font-semibold text-text-secondary uppercase tracking-wide mb-3">
              {t.wishes.executionResults}
            </h3>
            {/* Run-scoped anchors: the page may show the steps of more than one run, `step-N` alone would collide. */}
            <StepAnchorScope value={wish.currentRunId ?? ''}>
              {generatedRuns.content.find(run => run.id === wish.currentRunId)?.steps.map(step => (
                <ExecutionStepCard key={step.id} step={step} />
              ))}
            </StepAnchorScope>
          </div>
        </Card>
      ) : null}

      {(wish.resultSummary || wish.error) && (
        <Card>
          <div className="p-4">
            <h3 className="text-sm font-semibold text-text-secondary uppercase tracking-wide mb-3">
              {t.wishes.result}
            </h3>
            {wish.resultSummary && (
              <p className="text-sm text-text-primary"><PathLinkedOutput text={wish.resultSummary} /></p>
            )}
            {wish.error && (
              <p className="text-sm text-danger mt-2"><PathLinkedOutput text={wish.error} /></p>
            )}
          </div>
        </Card>
      )}
    </div>
  );
}
