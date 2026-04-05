import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { XCircle, Trash2, ChevronRight, ChevronDown, ExternalLink, Check, RefreshCw, MessageCircle, Save, Send, Eye, RotateCcw } from 'lucide-react';
import { Card, Button, Spinner, Badge } from '../ui';
import { LogViewer } from '../shared/LogViewer';
import { useWish, useRespondToWish, useCrystallizeWish } from '../../hooks/useWishes';
import { useExecutionWebSocket } from '../../hooks/useWebSocket';
import { useT } from '../../i18n';
import { formatRelativeTime } from '../../utils/formatTime';
import type { Wish, WishStatus } from '../../types';

export function WishDetail({ wishId, onCancel, onDelete }: {
  wishId: string;
  onCancel: () => void;
  onDelete: () => void;
}) {
  const t = useT();
  const navigate = useNavigate();
  const { data: wish } = useWish(wishId);
  const respond = useRespondToWish();
  const [historyOpen, setHistoryOpen] = useState(false);

  const isTerminal = (s: WishStatus) => s === 'COMPLETED' || s === 'FAILED' || s === 'CANCELLED';
  const isPaused = (s: WishStatus) => s === 'AWAITING_APPROVAL' || s === 'AWAITING_CLARIFICATION' || s === 'AWAITING_DECOMPOSE_APPROVAL';

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
                          onClick={() => navigate(`/runs`)}
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
      {(wish.resultSummary || wish.error) && (
        <Card>
          <div className="p-4">
            <h3 className="text-sm font-semibold text-text-secondary uppercase tracking-wide mb-3">
              {t.wishes.result}
            </h3>
            {wish.resultSummary && (
              <p className="text-sm text-text-primary">{wish.resultSummary}</p>
            )}
            {wish.error && (
              <p className="text-sm text-danger mt-2">{wish.error}</p>
            )}
          </div>
        </Card>
      )}
    </div>
  );
}

/* -- Approval Panel ------------------------------------------------- */

function ApprovalPanel({ wish }: { wish: Wish }) {
  const t = useT();
  const respond = useRespondToWish();
  const [feedback, setFeedback] = useState('');

  return (
    <Card className="border-2 border-accent">
      <div className="p-4 space-y-3">
        <h3 className="text-sm font-semibold text-accent uppercase tracking-wide">
          {t.wishes.status.awaiting_approval}
        </h3>
        <p className="text-sm text-text-secondary">{t.wishes.approvalRequired}</p>
        {wish.resultSummary && (
          <div className="p-3 rounded-lg bg-surface-raised text-sm text-text-primary">
            {wish.resultSummary}
          </div>
        )}
        <textarea
          value={feedback}
          onChange={(e) => setFeedback(e.target.value)}
          placeholder={t.wishes.refineFeedbackPlaceholder}
          className="w-full h-20 px-3 py-2 rounded-lg bg-surface-raised border border-border text-text-primary placeholder-text-tertiary resize-none focus:outline-none focus:ring-2 focus:ring-accent text-sm"
        />
        <div className="flex items-center gap-2">
          <Button
            onClick={() => respond.mutate({ id: wish.id, data: { action: 'ACCEPT' } })}
            disabled={respond.isPending}
            icon={<Check className="w-4 h-4" />}
          >
            {t.wishes.accept}
          </Button>
          <Button
            variant="secondary"
            onClick={() => respond.mutate({ id: wish.id, data: { action: 'REFINE', feedback } })}
            disabled={respond.isPending || !feedback.trim()}
            icon={<RefreshCw className="w-4 h-4" />}
          >
            {t.wishes.refine}
          </Button>
          <Button
            variant="ghost"
            onClick={() => respond.mutate({ id: wish.id, data: { action: 'CANCEL' } })}
            disabled={respond.isPending}
            icon={<XCircle className="w-4 h-4" />}
          >
            {t.wishes.cancel}
          </Button>
        </div>
      </div>
    </Card>
  );
}

/* -- Clarification Panel -------------------------------------------- */

function ClarificationPanel({ wish }: { wish: Wish }) {
  const t = useT();
  const respond = useRespondToWish();
  const [answer, setAnswer] = useState('');

  const question = typeof wish.metadata?.clarificationQuestion === 'string'
    ? wish.metadata.clarificationQuestion
    : '';

  return (
    <Card className="border-2 border-warning">
      <div className="p-4 space-y-3">
        <h3 className="text-sm font-semibold text-warning uppercase tracking-wide">
          {t.wishes.status.awaiting_clarification}
        </h3>
        <p className="text-sm text-text-secondary">{t.wishes.clarificationNeeded}</p>
        {question && (
          <div className="p-3 rounded-lg bg-surface-raised text-sm text-text-primary flex items-start gap-2">
            <MessageCircle className="w-4 h-4 text-warning shrink-0 mt-0.5" />
            {question}
          </div>
        )}
        <textarea
          value={answer}
          onChange={(e) => setAnswer(e.target.value)}
          placeholder={t.wishes.clarificationPlaceholder}
          className="w-full h-20 px-3 py-2 rounded-lg bg-surface-raised border border-border text-text-primary placeholder-text-tertiary resize-none focus:outline-none focus:ring-2 focus:ring-warning text-sm"
        />
        <div className="flex items-center gap-2">
          <Button
            onClick={() => respond.mutate({ id: wish.id, data: { action: 'CLARIFY', feedback: answer } })}
            disabled={respond.isPending || !answer.trim()}
            icon={<Send className="w-4 h-4" />}
          >
            {t.wishes.submitAnswer}
          </Button>
          <Button
            variant="ghost"
            onClick={() => respond.mutate({ id: wish.id, data: { action: 'CANCEL' } })}
            disabled={respond.isPending}
            icon={<XCircle className="w-4 h-4" />}
          >
            {t.wishes.cancel}
          </Button>
        </div>
      </div>
    </Card>
  );
}

/* -- Decompose Approval Panel --------------------------------------- */

function DecomposeApprovalPanel({ wish }: { wish: Wish }) {
  const t = useT();
  const respond = useRespondToWish();
  const [feedback, setFeedback] = useState('');

  return (
    <Card className="border-2 border-accent">
      <div className="p-4 space-y-3">
        <h3 className="text-sm font-semibold text-accent uppercase tracking-wide flex items-center gap-2">
          <Eye className="w-4 h-4" />
          {t.wishes.status.awaiting_decompose_approval}
        </h3>
        <p className="text-sm text-text-secondary">{t.wishes.decomposeApprovalRequired}</p>

        {/* Show decomposition details */}
        {wish.decomposition && (
          <div className="space-y-2">
            {typeof wish.decomposition.analysis === 'string' && (
              <div className="p-3 rounded-lg bg-surface-raised text-sm text-text-primary">
                {wish.decomposition.analysis}
              </div>
            )}
            {typeof wish.decomposition.strategy === 'string' && (
              <div className="p-3 rounded-lg bg-surface-raised text-sm text-text-tertiary">
                {wish.decomposition.strategy}
              </div>
            )}
          </div>
        )}

        {/* Show generated workflow names */}
        {wish.generatedWorkflowNames.length > 0 && (
          <div className="space-y-1">
            {wish.generatedWorkflowNames.map((name, i) => (
              <div key={i} className="flex items-center gap-2 p-2 rounded-lg bg-surface-raised">
                <span className="text-xs font-mono text-text-tertiary w-5 text-right">{i + 1}.</span>
                <span className="text-sm text-text-primary">{name}</span>
              </div>
            ))}
          </div>
        )}

        <textarea
          value={feedback}
          onChange={(e) => setFeedback(e.target.value)}
          placeholder={t.wishes.decomposeRefinePlaceholder}
          className="w-full h-20 px-3 py-2 rounded-lg bg-surface-raised border border-border text-text-primary placeholder-text-tertiary resize-none focus:outline-none focus:ring-2 focus:ring-accent text-sm"
        />
        <div className="flex items-center gap-2">
          <Button
            onClick={() => respond.mutate({ id: wish.id, data: { action: 'ACCEPT' } })}
            disabled={respond.isPending}
            icon={<Check className="w-4 h-4" />}
          >
            {t.wishes.approveDecomposition}
          </Button>
          <Button
            variant="secondary"
            onClick={() => respond.mutate({ id: wish.id, data: { action: 'REFINE', feedback } })}
            disabled={respond.isPending || !feedback.trim()}
            icon={<RefreshCw className="w-4 h-4" />}
          >
            {t.wishes.rejectDecomposition}
          </Button>
          <Button
            variant="ghost"
            onClick={() => respond.mutate({ id: wish.id, data: { action: 'CANCEL' } })}
            disabled={respond.isPending}
            icon={<XCircle className="w-4 h-4" />}
          >
            {t.wishes.cancel}
          </Button>
        </div>
      </div>
    </Card>
  );
}

/* -- Execution Logs Panel ------------------------------------------- */

function ExecutionLogsPanel({ runId }: { runId: string }) {
  const t = useT();
  const { logs, connected } = useExecutionWebSocket(runId);

  return (
    <Card>
      <div className="p-4 space-y-3">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold text-text-secondary uppercase tracking-wide">
            {t.wishes.executionLogs}
          </h3>
          {connected && (
            <span className="flex items-center gap-1.5 text-xs text-success">
              <span className="w-2 h-2 rounded-full bg-success animate-pulse" />
              Live
            </span>
          )}
        </div>
        <LogViewer logs={logs} maxHeight="400px" />
      </div>
    </Card>
  );
}

/* -- Crystallize Button --------------------------------------------- */

function CrystallizeButton({ wish }: { wish: Wish }) {
  const t = useT();
  const crystallize = useCrystallizeWish();
  const isCrystallized = typeof wish.metadata?.crystallizedTemplateId === 'string';

  if (isCrystallized) {
    return (
      <Card>
        <div className="p-4 flex items-center gap-2">
          <Badge variant="success">
            <Save className="w-3 h-3 mr-1 inline" />
            {t.wishes.crystallized}
          </Badge>
        </div>
      </Card>
    );
  }

  return (
    <Card>
      <div className="p-4">
        <Button
          variant="secondary"
          onClick={() => crystallize.mutate(wish.id)}
          disabled={crystallize.isPending}
          icon={<Save className="w-4 h-4" />}
        >
          {t.wishes.saveAsTemplate}
        </Button>
      </div>
    </Card>
  );
}
