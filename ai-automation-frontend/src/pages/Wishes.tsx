import { useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Sparkles, Send, XCircle, Trash2, ChevronRight, ChevronDown, ExternalLink, Check, RefreshCw, MessageCircle, Save, X, Eye } from 'lucide-react';
import { Card, Button, Input, Modal, Spinner, Badge } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { LogViewer } from '../components/shared/LogViewer';
import { useWishes, useCreateWish, useCancelWish, useDeleteWish, useWish, useRespondToWish, useCrystallizeWish } from '../hooks/useWishes';
import { useProjects } from '../hooks/useProjects';
import { useWorkflows } from '../hooks/useWorkflows';
import { useExecutionWebSocket } from '../hooks/useWebSocket';
import { useT } from '../i18n';
import { formatRelativeTime } from '../utils/formatTime';
import type { Wish, WishStatus } from '../types';

function WishStatusBadge({ status }: { status: WishStatus }) {
  const variant = status === 'COMPLETED' ? 'success'
    : status === 'FAILED' ? 'danger'
    : status === 'CANCELLED' ? 'warning'
    : (status === 'AWAITING_APPROVAL' || status === 'AWAITING_CLARIFICATION') ? 'warning'
    : status === 'AWAITING_DECOMPOSE_APPROVAL' ? 'accent'
    : (status === 'DECOMPOSING' || status === 'EXECUTING' || status === 'EVALUATING' || status === 'REFINING') ? 'accent'
    : 'default';
  const isActive = status === 'DECOMPOSING' || status === 'EXECUTING' || status === 'EVALUATING' || status === 'REFINING';
  const isAwaiting = status === 'AWAITING_APPROVAL' || status === 'AWAITING_CLARIFICATION' || status === 'AWAITING_DECOMPOSE_APPROVAL';
  return <Badge variant={variant} dot={isActive || isAwaiting}>{status}</Badge>;
}

export function Wishes() {
  const t = useT();
  const navigate = useNavigate();
  const { data: wishes, isLoading, error, refetch } = useWishes();
  const { data: projects } = useProjects();
  const createWish = useCreateWish();
  const cancelWish = useCancelWish();
  const deleteWish = useDeleteWish();

  const { data: allWorkflows } = useWorkflows();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [wishText, setWishText] = useState('');
  const [projectId, setProjectId] = useState<string>('');
  const [search, setSearch] = useState('');
  const [deleteConfirm, setDeleteConfirm] = useState<{ open: boolean; wish: Wish | null }>({ open: false, wish: null });
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [selectedWorkflowIds, setSelectedWorkflowIds] = useState<string[]>([]);
  const [workflowSearch, setWorkflowSearch] = useState('');

  const filtered = useMemo(() => {
    if (!wishes) return [];
    if (!search) return wishes;
    const q = search.toLowerCase();
    return wishes.filter(w => w.originalText.toLowerCase().includes(q) || w.projectName?.toLowerCase().includes(q));
  }, [wishes, search]);

  const selectedWish = useMemo(() => {
    if (selectedId) return filtered.find(w => w.id === selectedId) ?? filtered[0];
    return filtered[0];
  }, [filtered, selectedId]);

  const handleSubmit = () => {
    if (!wishText.trim()) return;
    createWish.mutate(
      {
        text: wishText,
        projectId: projectId || undefined,
        referenceWorkflowIds: selectedWorkflowIds.length > 0 ? selectedWorkflowIds : undefined,
      },
      {
        onSuccess: (wish) => {
          setWishText('');
          setSelectedWorkflowIds([]);
          setShowAdvanced(false);
          setSelectedId(wish.id);
        },
      },
    );
  };

  const workflowsByProject = useMemo(() => {
    if (!allWorkflows) return new Map<string, typeof allWorkflows>();
    const filtered = workflowSearch
      ? allWorkflows.filter(wf => wf.name.toLowerCase().includes(workflowSearch.toLowerCase()) || wf.projectName?.toLowerCase().includes(workflowSearch.toLowerCase()))
      : allWorkflows;
    const map = new Map<string, typeof allWorkflows>();
    for (const wf of filtered) {
      const key = wf.projectName || 'No Project';
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(wf);
    }
    return map;
  }, [allWorkflows, workflowSearch]);

  const toggleWorkflow = (id: string) => {
    setSelectedWorkflowIds(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]);
  };

  const handleDelete = () => {
    if (!deleteConfirm.wish) return;
    deleteWish.mutate(deleteConfirm.wish.id, {
      onSuccess: () => {
        setDeleteConfirm({ open: false, wish: null });
        if (selectedId === deleteConfirm.wish?.id) setSelectedId(null);
      },
    });
  };

  const isTerminal = (s: WishStatus) => s === 'COMPLETED' || s === 'FAILED' || s === 'CANCELLED';
  const isPaused = (s: WishStatus) => s === 'AWAITING_APPROVAL' || s === 'AWAITING_CLARIFICATION' || s === 'AWAITING_DECOMPOSE_APPROVAL';

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }

  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-text-primary">{t.wishes.title}</h1>
        <p className="text-text-secondary mt-1">{t.wishes.subtitle}</p>
      </div>

      {/* Input Area */}
      <Card>
        <div className="p-4 space-y-3">
          <textarea
            value={wishText}
            onChange={(e) => setWishText(e.target.value)}
            placeholder={t.wishes.placeholder}
            className="w-full h-24 px-3 py-2 rounded-lg bg-surface-raised border border-border text-text-primary placeholder-text-tertiary resize-none focus:outline-none focus:ring-2 focus:ring-accent"
          />
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <select
                value={projectId}
                onChange={(e) => setProjectId(e.target.value)}
                className="px-3 py-2 rounded-lg bg-surface-raised border border-border text-text-primary text-sm focus:outline-none focus:ring-2 focus:ring-accent"
              >
                <option value="">{t.wishes.projectHint}</option>
                {projects?.map(p => (
                  <option key={p.id} value={p.id}>{p.name}</option>
                ))}
              </select>
              <button
                type="button"
                onClick={() => setShowAdvanced(v => !v)}
                className="flex items-center gap-1 px-2 py-1.5 text-xs text-text-secondary hover:text-text-primary transition-colors"
              >
                {showAdvanced ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
                {showAdvanced ? t.wishes.hideAdvanced : t.wishes.advanced}
              </button>
            </div>
            <Button
              onClick={handleSubmit}
              disabled={!wishText.trim() || createWish.isPending}
              icon={<Send className="w-4 h-4" />}
            >
              {t.wishes.create}
            </Button>
          </div>

          {/* Advanced: Reference Workflow Selector */}
          {showAdvanced && (
            <div className="space-y-2 pt-1">
              <div>
                <label className="text-xs font-medium text-text-secondary">{t.wishes.referenceWorkflows}</label>
                <p className="text-xs text-text-tertiary">{t.wishes.referenceWorkflowsHint}</p>
                <ul className="mt-1.5 space-y-0.5">
                  {t.wishes.referenceWorkflowsTips.map((tip, i) => (
                    <li key={i} className="text-[11px] text-text-tertiary flex items-start gap-1.5">
                      <span className="mt-0.5 shrink-0 w-1 h-1 rounded-full bg-text-tertiary/50" />
                      {tip}
                    </li>
                  ))}
                </ul>
              </div>

              {/* Selected chips */}
              {selectedWorkflowIds.length > 0 && (
                <div className="flex flex-wrap gap-1.5">
                  {selectedWorkflowIds.map(id => {
                    const wf = allWorkflows?.find(w => w.id === id);
                    return (
                      <span key={id} className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-accent/15 text-accent text-xs">
                        {wf?.name ?? id}
                        <button type="button" onClick={() => toggleWorkflow(id)} className="hover:text-accent/70">
                          <X className="w-3 h-3" />
                        </button>
                      </span>
                    );
                  })}
                  <span className="text-xs text-text-tertiary self-center">{selectedWorkflowIds.length} {t.wishes.selectedCount}</span>
                </div>
              )}

              {/* Search + grouped list */}
              <input
                value={workflowSearch}
                onChange={(e) => setWorkflowSearch(e.target.value)}
                placeholder={t.wishes.searchWorkflows}
                className="w-full px-3 py-1.5 rounded-lg bg-surface-raised border border-border text-text-primary placeholder-text-tertiary text-sm focus:outline-none focus:ring-2 focus:ring-accent"
              />
              <div className="max-h-48 overflow-y-auto rounded-lg border border-border bg-surface-raised">
                {[...workflowsByProject.entries()].map(([projName, wfs]) => (
                  <div key={projName}>
                    <div className="px-3 py-1 text-[10px] font-semibold text-text-tertiary uppercase tracking-wide bg-surface-base sticky top-0">
                      {projName}
                    </div>
                    {wfs?.map(wf => (
                      <label
                        key={wf.id}
                        className="flex items-center gap-2 px-3 py-1.5 hover:bg-surface-base cursor-pointer text-sm"
                      >
                        <input
                          type="checkbox"
                          checked={selectedWorkflowIds.includes(wf.id)}
                          onChange={() => toggleWorkflow(wf.id)}
                          className="rounded border-border text-accent focus:ring-accent"
                        />
                        <span className="text-text-primary truncate">{wf.name}</span>
                      </label>
                    ))}
                  </div>
                ))}
                {workflowsByProject.size === 0 && (
                  <p className="px-3 py-2 text-xs text-text-tertiary">{t.common.noResults}</p>
                )}
              </div>
            </div>
          )}
        </div>
      </Card>

      {/* Content */}
      {(!wishes || wishes.length === 0) ? (
        <EmptyState
          icon={<Sparkles className="w-8 h-8 text-text-tertiary" />}
          title={t.wishes.noWishes}
        />
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Left: Wish List */}
          <div className="lg:col-span-1 space-y-3">
            <Input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t.common.search}
            />
            <div className="space-y-2">
              {filtered.map(wish => (
                <Card
                  key={wish.id}
                  className={`cursor-pointer transition-all ${selectedWish?.id === wish.id ? 'ring-2 ring-accent' : ''}`}
                  onClick={() => setSelectedId(wish.id)}
                >
                  <div className="p-3">
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0 flex-1">
                        <p className="text-sm font-medium text-text-primary line-clamp-2">{wish.originalText}</p>
                        <div className="flex items-center gap-2 mt-1.5">
                          <WishStatusBadge status={wish.status} />
                          {wish.iterationCount > 0 && (
                            <span className="text-xs text-text-tertiary">
                              {t.wishes.iteration} {wish.iterationCount}/{wish.maxIterations}
                            </span>
                          )}
                        </div>
                        {wish.projectName && (
                          <p className="text-xs text-text-tertiary mt-1">{wish.projectName}</p>
                        )}
                      </div>
                      <span className="text-xs text-text-tertiary whitespace-nowrap">
                        {formatRelativeTime(wish.createdAt)}
                      </span>
                    </div>
                  </div>
                </Card>
              ))}
            </div>
          </div>

          {/* Right: Detail Panel */}
          <div className="lg:col-span-2">
            {selectedWish ? (
              <WishDetail
                wishId={selectedWish.id}
                onCancel={() => cancelWish.mutate(selectedWish.id)}
                onDelete={() => setDeleteConfirm({ open: true, wish: selectedWish })}
                navigate={navigate}
                isTerminal={isTerminal}
                isPaused={isPaused}
              />
            ) : (
              <Card className="p-8 text-center text-text-tertiary">
                {t.wishes.noWishes}
              </Card>
            )}
          </div>
        </div>
      )}

      {/* Delete Confirm */}
      <Modal open={deleteConfirm.open} onClose={() => setDeleteConfirm({ open: false, wish: null })} title={t.common.delete}>
        <p className="text-text-secondary mb-4">{t.wishes.deleteConfirm}</p>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setDeleteConfirm({ open: false, wish: null })}>{t.common.cancel}</Button>
          <Button variant="danger" onClick={handleDelete} disabled={deleteWish.isPending}>{t.common.delete}</Button>
        </div>
      </Modal>
    </div>
  );
}

/* ── Wish Detail Panel ──────────────────────────────── */

function WishDetail({ wishId, onCancel, onDelete, navigate, isTerminal, isPaused }: {
  wishId: string;
  onCancel: () => void;
  onDelete: () => void;
  navigate: ReturnType<typeof useNavigate>;
  isTerminal: (s: WishStatus) => boolean;
  isPaused: (s: WishStatus) => boolean;
}) {
  const t = useT();
  const { data: wish } = useWish(wishId);

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
              {isTerminal(wish.status) && (
                <Button variant="ghost" size="sm" icon={<Trash2 className="w-4 h-4" />} onClick={onDelete} />
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

/* ── Approval Panel ──────────────────────────────────── */

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

/* ── Clarification Panel ─────────────────────────────── */

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

/* ── Decompose Approval Panel ──────────────────────── */

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

/* ── Execution Logs Panel ──────────────────────────── */

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

/* ── Crystallize Button ──────────────────────────────── */

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
