import { useState } from 'react';
import { XCircle, Check, RefreshCw } from 'lucide-react';
import { Card, Button } from '../ui';
import { useRespondToWish } from '../../hooks/useWishes';
import { useT } from '../../i18n';
import type { Wish } from '../../types';

export function ApprovalPanel({ wish }: { wish: Wish }) {
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
