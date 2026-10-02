import { XCircle, Trash2, RefreshCw, RotateCcw } from 'lucide-react';
import { Card, Button, Badge } from '../ui';
import { useRespondToWish } from '../../hooks/useWishes';
import { useT } from '../../i18n';
import type { Wish, WishStatus } from '../../types';

const isTerminal = (s: WishStatus) => s === 'COMPLETED' || s === 'FAILED' || s === 'CANCELLED';
const isPaused = (s: WishStatus) => s === 'AWAITING_APPROVAL' || s === 'AWAITING_CLARIFICATION' || s === 'AWAITING_DECOMPOSE_APPROVAL';

const statusSteps: WishStatus[] = ['RECEIVED', 'DECOMPOSING', 'EXECUTING', 'EVALUATING', 'COMPLETED'];

/** Timeline position of a status: waiting states sit at the phase they wait in, a refinement decomposes anew. */
function timelineStep(status: WishStatus): WishStatus {
  switch (status) {
    case 'AWAITING_CLARIFICATION':
    case 'AWAITING_DECOMPOSE_APPROVAL':
    case 'REFINING':
      return 'DECOMPOSING';
    case 'AWAITING_APPROVAL':
      return 'EVALUATING';
    default:
      return status;
  }
}

/**
 * Where a failed or cancelled wish stopped: the phase the backend recorded (`stoppedPhase`, V97). Older backends
 * and wishes that stopped before V97 have none — then the newest iteration entry tells: an evaluation, else a
 * started run, else a finished decomposition (the iteration counter only advances once its workflows exist) mean
 * the wish got that far. Without an entry, or with a decomposition that never finished, it stopped while decomposing.
 */
function stoppedStep(wish: Wish): WishStatus {
  if (wish.stoppedPhase) return timelineStep(wish.stoppedPhase);
  const latest = wish.iterationHistory.at(-1);
  if (!latest) return 'DECOMPOSING';
  if (latest.evaluation) return 'EVALUATING';
  if (latest.runId) return 'EXECUTING';
  const unfinished = typeof latest.iteration === 'number' && latest.iteration > wish.iterationCount;
  return unfinished ? 'DECOMPOSING' : 'EXECUTING';
}

/** Head of the wish detail: text, project, actions (cancel, retry, delete), status timeline and iteration counter. */
export function WishHeaderCard({ wish, onCancel, onDelete }: {
  wish: Wish;
  onCancel: () => void;
  onDelete: () => void;
}) {
  const t = useT();
  const respond = useRespondToWish();

  // A failed or cancelled wish sits where it stopped; every other status at its timeline position
  const failed = wish.status === 'FAILED';
  const cancelled = wish.status === 'CANCELLED';
  const completed = wish.status === 'COMPLETED';
  const effectiveStatus: WishStatus = failed || cancelled ? stoppedStep(wish) : timelineStep(wish.status);
  const currentIndex = statusSteps.indexOf(effectiveStatus);

  return (
    <Card>
      <div className="p-4">
        <div className="flex items-start justify-between mb-3">
          <div className="flex-1 min-w-0">
            <p className="text-text font-medium">{wish.originalText}</p>
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
            const isPastStep = currentIndex > i || completed;
            const isFailed = failed && isActive;
            const isCancelledHere = cancelled && isActive;
            const isAwaitingHere = isActive && isPaused(wish.status);
            return (
              <div key={step} className="flex items-center gap-1 flex-1">
                {/* A completed wish has nothing running: all bars done, none pulsing */}
                <div className={`h-1.5 flex-1 rounded-full ${
                  completed ? 'bg-success' :
                  isFailed ? 'bg-danger' :
                  isCancelledHere ? 'bg-warning' :
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
            const isHighlighted = effectiveStatus === step;
            const label = (isPaused(wish.status) || failed || cancelled) && isHighlighted
              ? (t.wishes.status[wish.status.toLowerCase() as keyof typeof t.wishes.status] ?? wish.status)
              : (t.wishes.status[step.toLowerCase() as keyof typeof t.wishes.status] ?? step);
            return (
              <span key={step} className={`text-[10px] ${
                isHighlighted && isPaused(wish.status) ? 'text-warning font-medium' :
                isHighlighted && failed ? 'text-danger font-medium' :
                isHighlighted && cancelled ? 'text-warning font-medium' :
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
  );
}
