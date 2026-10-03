import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronRight, ExternalLink } from 'lucide-react';
import { Card, Spinner } from '../ui';
import { useWish } from '../../hooks/useWishes';
import { useT } from '../../i18n';
import { ApprovalPanel } from './ApprovalPanel';
import { ClarificationPanel } from './ClarificationPanel';
import { DecomposeApprovalPanel } from './DecomposeApprovalPanel';
import { ExecutionLogsPanel } from './ExecutionLogsPanel';
import { CrystallizeButton } from './CrystallizeButton';
import { WishHeaderCard } from './WishHeaderCard';
import { WishIterationHistory } from './WishIterationHistory';
import { ExecutionStepCard } from '../workflow/ExecutionStepCard';
import { StepAnchorScope } from '../workflow/stepAnchors';
import { PathLinkedOutput } from '../shared/PathLinkedOutput';
import { useWorkflowRun } from '../../hooks/useWorkflows';

export function WishDetail({ wishId, onCancel, onDelete }: {
  wishId: string;
  onCancel: () => void;
  onDelete: () => void;
}) {
  const t = useT();
  const navigate = useNavigate();
  const { data: wish } = useWish(wishId);
  // Only the current run's steps are shown — loaded on their own, the run list carries none
  const { data: currentRun } = useWorkflowRun(wish?.generatedWorkflowIds?.[0] ?? '', wish?.currentRunId ?? undefined);
  // Lives here, not in the history card: stays open when another wish is selected
  const [historyOpen, setHistoryOpen] = useState(false);

  if (!wish) return <Card className="p-8 text-center"><Spinner /></Card>;

  return (
    <div className="space-y-4">
      <WishHeaderCard wish={wish} onCancel={onCancel} onDelete={onDelete} />

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
              <p className="text-sm text-text mb-2">{wish.decomposition.analysis}</p>
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
                    <span className="text-sm text-text">
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
                  {t.common.chain} <ChevronRight className="w-3.5 h-3.5" />
                </span>
              </div>
            )}
          </div>
        </Card>
      )}

      {/* Iteration History */}
      {wish.iterationHistory && wish.iterationHistory.length > 0 && (
        <WishIterationHistory wish={wish} open={historyOpen} onToggle={() => setHistoryOpen(v => !v)} />
      )}

      {/* Crystallize Button */}
      {wish.status === 'COMPLETED' && wish.generatedWorkflowIds.length > 0 && (
        <CrystallizeButton wish={wish} />
      )}

      {/* Result */}
      {currentRun?.id === wish.currentRunId && currentRun?.steps?.length ? (
        <Card>
          <div className="p-4 space-y-2">
            <h3 className="text-sm font-semibold text-text-secondary uppercase tracking-wide mb-3">
              {t.wishes.executionResults}
            </h3>
            {/* Run-scoped anchors: the page may show the steps of more than one run, `step-N` alone would collide. */}
            <StepAnchorScope value={wish.currentRunId ?? ''}>
              {currentRun.steps.map(step => (
                <ExecutionStepCard key={step.id} step={step}
                  runActive={currentRun.status === 'RUNNING' || currentRun.status === 'PAUSED'} />
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
              <p className="text-sm text-text"><PathLinkedOutput text={wish.resultSummary} /></p>
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
