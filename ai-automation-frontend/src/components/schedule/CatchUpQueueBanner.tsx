import { ListOrdered } from 'lucide-react';
import { useT } from '../../i18n';
import type { CatchUpQueueStatus } from '../../types';

/**
 * Shows the sequential catch-up queue after a resume or cold start: the missed occurrence
 * currently running and the ones still waiting. Renders nothing while the queue is empty.
 */
export function CatchUpQueueBanner({ queue }: { queue: CatchUpQueueStatus | undefined }) {
  const t = useT();
  if (!queue || (!queue.active && queue.pending.length === 0)) return null;

  return (
    <div
      className="flex items-start gap-3 px-4 py-3 rounded-lg bg-accent/10 border border-accent/30 text-accent"
      data-testid="catch-up-queue-banner"
    >
      <ListOrdered className="w-4 h-4 shrink-0 mt-0.5" />
      <div className="text-sm space-y-0.5">
        <div className="font-medium">{t.schedules.catchUpQueueTitle}</div>
        {queue.active && (
          <div>
            {t.schedules.catchUpQueueRunning}: <span className="font-medium">{queue.active.targetName}</span>
          </div>
        )}
        {queue.pending.length > 0 && (
          <div>
            {t.schedules.catchUpQueueWaiting} ({queue.pending.length}):{' '}
            {queue.pending.map(p => p.targetName).join(' → ')}
          </div>
        )}
      </div>
    </div>
  );
}
