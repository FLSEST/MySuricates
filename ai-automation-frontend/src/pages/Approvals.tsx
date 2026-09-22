import { Hint } from '../components/hints/Hint';
import { Link } from 'react-router-dom';
import { ArrowRight, Hand } from 'lucide-react';
import { Button, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ApprovalQuestion } from '../components/shared/ApprovalQuestion';
import { usePendingInputs, isRejectOption, type PendingInput } from '../hooks/usePendingInputs';
import { useSubmitApproval } from '../hooks/useWorkflows';
import { useToast } from '../hooks/useToast';
import { useI18nStore, useT } from '../i18n';
import { formatRelativeTime, formatTimeUntil } from '../utils/formatTime';

/** Every run waiting for an answer, oldest first — answer approvals right here, or jump to the run. */
export function Approvals() {
  const t = useT();
  const { data, isLoading } = usePendingInputs();
  const pending = data ?? [];

  return (
    <div className="space-y-4 p-6 max-w-4xl">
      <header>
        <h1 className="text-2xl font-bold text-text flex items-center gap-2">
          <Hand className="w-6 h-6 text-human" />
          {t.approvals.title}
        </h1>
        <p className="text-sm text-text-secondary mt-1">{t.approvals.subtitle}</p>
      </header>

      <Hint id="approvals-inbox" />

      {isLoading ? (
        <div className="flex justify-center py-12"><Spinner /></div>
      ) : pending.length === 0 ? (
        <EmptyState
          icon={<Hand className="w-8 h-8 text-text-tertiary" />}
          title={t.approvals.emptyTitle}
          description={t.approvals.emptyDescription}
        />
      ) : (
        <ul className="space-y-4">
          {pending.map((item) => (
            <li key={`${item.runId}-${item.blockId}`}>
              <PendingInputCard item={item} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function runPath(item: PendingInput): string {
  return item.workflowId ? `/workflows/${item.workflowId}/run/${item.runId}` : `/runs/${item.runId}`;
}

function PendingInputCard({ item }: { item: PendingInput }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const toast = useToast();
  const submit = useSubmitApproval();
  const answering = submit.isPending && submit.variables?.runId === item.runId;
  const isApproval = item.type === 'approval';

  const answer = (option: string) =>
    submit.mutate({ runId: item.runId, answer: option }, { onSuccess: () => toast.success(t.approvals.answered) });

  return (
    <article className="rounded-xl border border-card-border bg-surface-raised p-4 space-y-3" aria-label={item.workflowName ?? t.approvals.deletedWorkflow}>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
        <span className="rounded-full bg-human-subtle px-2 py-0.5 text-xs font-semibold text-human">
          {isApproval ? t.approvals.typeApproval : t.approvals.typeHumanInput}
        </span>
        {item.workflowId ? (
          <Link to={`/workflows/${item.workflowId}`} className="font-semibold text-text hover:text-accent">
            {item.workflowName ?? item.workflowId}
          </Link>
        ) : (
          <span className="font-semibold text-text">{item.workflowName ?? t.approvals.deletedWorkflow}</span>
        )}
        <span className="text-text-tertiary">
          {t.approvals.step} {item.stepIndex + 1}
        </span>
        <span className="text-text-secondary">
          {t.approvals.asked} {formatRelativeTime(item.requestedAt, locale)}
        </span>
        {item.timeoutAt && (
          <span className="text-text-secondary">
            {t.approvals.timesOut} {formatTimeUntil(item.timeoutAt, locale)}
          </span>
        )}
      </div>

      <ApprovalQuestion question={item.question} maxHeightClass="max-h-[40vh]" />

      <div className="flex flex-wrap items-center gap-2">
        {isApproval ? (
          item.options.map((option) => (
            <Button
              key={option}
              variant={isRejectOption(option, item.rejectOptions) ? 'danger' : 'primary'}
              size="sm"
              onClick={() => answer(option)}
              loading={answering}
              className="min-w-[100px]"
            >
              {option}
            </Button>
          ))
        ) : (
          <p className="text-sm text-text-secondary">{t.approvals.humanInputHint}</p>
        )}
        <Link
          to={runPath(item)}
          className="ml-auto inline-flex items-center gap-1 text-sm font-medium text-accent hover:underline"
        >
          {t.approvals.openRun}
          <ArrowRight className="w-4 h-4" />
        </Link>
      </div>
    </article>
  );
}
