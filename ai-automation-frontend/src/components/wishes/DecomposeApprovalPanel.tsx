import { useState } from 'react';
import { XCircle, Check, RefreshCw, Eye } from 'lucide-react';
import { Card, Button } from '../ui';
import { useRespondToWish } from '../../hooks/useWishes';
import { useT } from '../../i18n';
import type { Wish } from '../../types';

export function DecomposeApprovalPanel({ wish }: { wish: Wish }) {
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
              <div className="p-3 rounded-lg bg-surface-raised text-sm text-text">
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
                <span className="text-sm text-text">{name}</span>
              </div>
            ))}
          </div>
        )}

        <textarea
          value={feedback}
          onChange={(e) => setFeedback(e.target.value)}
          placeholder={t.wishes.decomposeRefinePlaceholder}
          className="w-full h-20 px-3 py-2 rounded-lg bg-surface-raised border border-border text-text placeholder-text-tertiary resize-none focus:outline-none focus:ring-2 focus:ring-accent text-sm"
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
