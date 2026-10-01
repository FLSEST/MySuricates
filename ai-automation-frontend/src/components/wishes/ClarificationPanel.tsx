import { useState } from 'react';
import { XCircle, MessageCircle, Send } from 'lucide-react';
import { Card, Button } from '../ui';
import { useRespondToWish } from '../../hooks/useWishes';
import { useT } from '../../i18n';
import type { Wish } from '../../types';

export function ClarificationPanel({ wish }: { wish: Wish }) {
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
          <div className="p-3 rounded-lg bg-surface-raised text-sm text-text flex items-start gap-2">
            <MessageCircle className="w-4 h-4 text-warning shrink-0 mt-0.5" />
            {question}
          </div>
        )}
        <textarea
          value={answer}
          onChange={(e) => setAnswer(e.target.value)}
          placeholder={t.wishes.clarificationPlaceholder}
          className="w-full h-20 px-3 py-2 rounded-lg bg-surface-raised border border-border text-text placeholder-text-tertiary resize-none focus:outline-none focus:ring-2 focus:ring-warning text-sm"
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
