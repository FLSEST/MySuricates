import { XCircle, Trash2, RefreshCw, RotateCcw } from 'lucide-react';
import { Card, Button, Badge } from '../ui';
import { useRespondToWish } from '../../hooks/useWishes';
import { useT } from '../../i18n';
import type { Wish, WishStatus } from '../../types';

const isTerminal = (s: WishStatus) => s === 'COMPLETED' || s === 'FAILED' || s === 'CANCELLED';
const isPaused = (s: WishStatus) => s === 'AWAITING_APPROVAL' || s === 'AWAITING_CLARIFICATION' || s === 'AWAITING_DECOMPOSE_APPROVAL';

const statusSteps: WishStatus[] = ['RECEIVED', 'DECOMPOSING', 'EXECUTING', 'EVALUATING', 'COMPLETED'];

/** Head of the wish detail: text, project, actions (cancel, retry, delete), status timeline and iteration counter. */
export function WishHeaderCard({ wish, onCancel, onDelete }: {
  wish: Wish;
  onCancel: () => void;
  onDelete: () => void;
}) {
  const t = useT();
  const respond = useRespondToWish();

  // Map AWAITING_* statuses to their timeline position
  const effectiveStatus: WishStatus =
    wish.status === 'AWAITING_CLARIFICATION' ? 'DECOMPOSING' :
    wish.status === 'AWAITING_DECOMPOSE_APPROVAL' ? 'DECOMPOSING' :
    wish.status === 'AWAITING_APPROVAL' ? 'EVALUATING' :
    wish.status;
  const currentIndex = statusSteps.indexOf(effectiveStatus);

  return (
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
            // Never true today: FAILED is no timeline step, currentIndex is -1 (TODO 5165ec90)
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
  );
}
