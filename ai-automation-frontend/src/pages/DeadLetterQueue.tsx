import { useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, RotateCcw, Trash2, ChevronLeft, ChevronRight } from 'lucide-react';
import { Button, Modal, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import {
  useDeadLetters,
  useRetryDeadLetter,
  useDiscardDeadLetter,
  type DeadLetterEntry,
  type DeadLetterStatus,
} from '../hooks/useDeadLetters';
import { useT } from '../i18n';
import { formatRelativeTime } from '../utils/formatTime';

const PAGE_SIZE = 20;

const statusBadgeStyle: Record<DeadLetterStatus, string> = {
  OPEN: 'bg-danger-subtle text-danger',
  RETRIED: 'bg-info-subtle text-info',
  DISCARDED: 'bg-surface-hover text-text-secondary',
};

export function DeadLetterQueue() {
  const t = useT();
  const [statusFilter, setStatusFilter] = useState<DeadLetterStatus | 'ALL'>('OPEN');
  const [page, setPage] = useState(0);
  const [retryTarget, setRetryTarget] = useState<DeadLetterEntry | null>(null);
  const [discardTarget, setDiscardTarget] = useState<DeadLetterEntry | null>(null);

  const { data, isLoading } = useDeadLetters(statusFilter, page, PAGE_SIZE);
  const retry = useRetryDeadLetter();
  const discard = useDiscardDeadLetter();

  const entries = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;

  const handleRetry = () => {
    if (!retryTarget) return;
    retry.mutate(retryTarget.id, { onSuccess: () => setRetryTarget(null) });
  };

  const handleDiscard = () => {
    if (!discardTarget) return;
    discard.mutate(discardTarget.id, { onSuccess: () => setDiscardTarget(null) });
  };

  return (
    <div className="space-y-4 p-6">
      <header className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-text flex items-center gap-2">
            <AlertTriangle className="w-6 h-6 text-warning" />
            {t.dlq.title}
          </h1>
          <p className="text-sm text-text-secondary mt-1">{t.dlq.subtitle}</p>
        </div>
      </header>

      <div className="flex items-center gap-2">
        {(['OPEN', 'RETRIED', 'DISCARDED', 'ALL'] as const).map(s => (
          <button
            key={s}
            onClick={() => { setStatusFilter(s); setPage(0); }}
            className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
              statusFilter === s
                ? 'bg-accent text-text-inverse'
                : 'bg-surface-raised text-text-secondary hover:bg-surface'
            }`}
          >
            {s === 'OPEN' ? t.dlq.statusOpen
              : s === 'RETRIED' ? t.dlq.statusRetried
              : s === 'DISCARDED' ? t.dlq.statusDiscarded
              : t.dlq.statusAll}
          </button>
        ))}
      </div>

      {isLoading ? (
        <div className="flex justify-center py-12"><Spinner /></div>
      ) : entries.length === 0 ? (
        <EmptyState
          icon={<AlertTriangle className="w-10 h-10" />}
          title={t.dlq.empty}
          description=""
        />
      ) : (
        <div className="space-y-2">
          {entries.map(entry => (
            <DlqRow
              key={entry.id}
              entry={entry}
              onRetry={() => setRetryTarget(entry)}
              onDiscard={() => setDiscardTarget(entry)}
            />
          ))}
        </div>
      )}

      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 pt-2">
          <Button
            variant="ghost"
            disabled={page === 0}
            onClick={() => setPage(p => Math.max(0, p - 1))}
          >
            <ChevronLeft className="w-4 h-4" />
          </Button>
          <span className="text-sm text-text-secondary">
            {page + 1} / {totalPages}
          </span>
          <Button
            variant="ghost"
            disabled={page >= totalPages - 1}
            onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
          >
            <ChevronRight className="w-4 h-4" />
          </Button>
        </div>
      )}

      <Modal
        open={!!retryTarget}
        onClose={() => setRetryTarget(null)}
        title={t.dlq.retryConfirm}
      >
        <p className="text-text-secondary mb-4">{t.dlq.retryConfirmHint}</p>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setRetryTarget(null)}>
            {t.common.cancel}
          </Button>
          <Button onClick={handleRetry} loading={retry.isPending}>
            {t.dlq.retry}
          </Button>
        </div>
      </Modal>

      <Modal
        open={!!discardTarget}
        onClose={() => setDiscardTarget(null)}
        title={t.dlq.discardConfirm}
      >
        <p className="text-text-secondary mb-4">{t.dlq.discardConfirmHint}</p>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setDiscardTarget(null)}>
            {t.common.cancel}
          </Button>
          <Button variant="danger" onClick={handleDiscard} loading={discard.isPending}>
            {t.dlq.discard}
          </Button>
        </div>
      </Modal>
    </div>
  );
}

function DlqRow({
  entry,
  onRetry,
  onDiscard,
}: {
  entry: DeadLetterEntry;
  onRetry: () => void;
  onDiscard: () => void;
}) {
  const t = useT();
  const isOpen = entry.status === 'OPEN';

  return (
    <div className="bg-surface-raised border border-border rounded-lg p-4">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            <span className={`text-xs font-medium px-2 py-0.5 rounded ${statusBadgeStyle[entry.status]}`}>
              {entry.status === 'OPEN' ? t.dlq.statusOpen
                : entry.status === 'RETRIED' ? t.dlq.statusRetried
                : t.dlq.statusDiscarded}
            </span>
            {entry.workflowId ? (
              <Link
                to={`/workflows/${entry.workflowId}`}
                className="font-medium text-text hover:text-accent truncate"
              >
                {entry.workflowName ?? entry.workflowId}
              </Link>
            ) : (
              <span className="font-medium text-text truncate">
                {entry.workflowName ?? '—'}
              </span>
            )}
            {entry.failedStepIndex != null && (
              <span className="text-xs text-text-tertiary">
                {t.dlq.failedStep} {entry.failedStepIndex}
              </span>
            )}
          </div>

          {entry.errorMessage && (
            <p className="text-sm text-text-secondary mt-1.5 line-clamp-2 font-mono">
              {entry.errorMessage}
            </p>
          )}

          <div className="flex items-center gap-3 mt-2 text-xs text-text-tertiary">
            <span>{t.dlq.failedAt}: {formatRelativeTime(entry.lastFailureAt)}</span>
            {entry.retryCount > 0 && (
              <span>{t.dlq.retryCount}: {entry.retryCount}</span>
            )}
            {entry.workflowRunId && (
              <Link
                to={`/runs/${entry.workflowRunId}`}
                className="text-accent hover:underline"
              >
                Run →
              </Link>
            )}
          </div>
        </div>

        {isOpen && (
          <div className="flex items-center gap-2 shrink-0">
            <Button variant="ghost" onClick={onRetry} title={t.dlq.retry}>
              <RotateCcw className="w-4 h-4" />
            </Button>
            <Button variant="ghost" onClick={onDiscard} title={t.dlq.discard}>
              <Trash2 className="w-4 h-4" />
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}
