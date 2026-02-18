import { Button } from '../ui';
import { useT } from '../../i18n';
import type { PendingApproval } from '../../hooks/useWebSocket';

interface ApprovalDialogProps {
  approval: PendingApproval;
  onSubmit: (answer: string) => void;
  loading?: boolean;
}

export function ApprovalDialog({ approval, onSubmit, loading }: ApprovalDialogProps) {
  const t = useT();

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 animate-fade-in">
      <div className="bg-surface-raised border border-border rounded-2xl shadow-xl p-6 max-w-md w-full mx-4">
        <h3 className="text-lg font-bold text-text mb-1">
          {t.workflows.execution.approval.title}
        </h3>
        <p className="text-sm text-text-secondary mb-4">
          {t.workflows.execution.approval.description}
        </p>

        <div className="bg-surface rounded-lg p-4 mb-6 border border-border">
          <p className="text-sm font-medium text-text">{approval.question}</p>
          <p className="text-xs text-text-tertiary mt-1">Step {approval.stepIndex + 1}</p>
        </div>

        <div className="flex flex-wrap gap-2">
          {approval.options.map((option) => {
            const isNo = option.toLowerCase() === 'no';
            return (
              <Button
                key={option}
                variant={isNo ? 'danger' : 'primary'}
                size="sm"
                onClick={() => onSubmit(option)}
                loading={loading}
                className="flex-1 min-w-[100px]"
              >
                {option}
              </Button>
            );
          })}
        </div>
      </div>
    </div>
  );
}
