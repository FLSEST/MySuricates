import { X } from 'lucide-react';
import { Button } from '../ui';
import { useT } from '../../i18n';
import type { PendingApproval } from '../../hooks/useWebSocket';
import { isRejectOption } from '../../hooks/usePendingInputs';
import { ApprovalQuestion } from './ApprovalQuestion';

interface ApprovalDialogProps {
  approval: PendingApproval;
  onSubmit: (answer: string) => void;
  onDismiss?: () => void;
  loading?: boolean;
}

export function ApprovalDialog({ approval, onSubmit, onDismiss, loading }: ApprovalDialogProps) {
  const t = useT();

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 animate-fade-in">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="approval-dialog-title"
        className="bg-surface-raised border border-border rounded-2xl shadow-xl p-6 max-w-2xl w-full mx-4 relative flex flex-col max-h-[90vh]"
      >
        {onDismiss && (
          <button
            onClick={onDismiss}
            className="absolute top-3 right-3 p-1 rounded-lg text-text-tertiary hover:text-text hover:bg-surface transition-colors"
            aria-label={t.common.close}
          >
            <X className="w-4 h-4" />
          </button>
        )}
        <h3 id="approval-dialog-title" className="text-lg font-bold text-text mb-1">
          {t.workflows.execution.approval.title}
        </h3>
        <p className="text-sm text-text-secondary mb-4">
          {t.workflows.execution.approval.description}
        </p>

        <div className="mb-6 min-h-0 flex flex-col">
          <ApprovalQuestion question={approval.question} maxHeightClass="max-h-[60vh]" />
          <p className="text-xs text-text-tertiary mt-1">Step {approval.stepIndex + 1}</p>
        </div>

        <div className="flex flex-wrap gap-2">
          {approval.options.map((option) => (
            <Button
              key={option}
              variant={isRejectOption(option, approval.rejectOptions) ? 'danger' : 'primary'}
              size="sm"
              onClick={() => onSubmit(option)}
              loading={loading}
              className="flex-1 min-w-[100px]"
            >
              {option}
            </Button>
          ))}
        </div>
      </div>
    </div>
  );
}
