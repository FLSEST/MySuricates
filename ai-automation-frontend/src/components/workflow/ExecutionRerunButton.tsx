import { RotateCcw } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { Button } from '../ui';
import { useRetryExecution } from '../../hooks/useWorkflows';
import { useT } from '../../i18n';
import type { WorkflowRun } from '../../types';

/** A rerun starts a fresh execution with the original input context. */
export function ExecutionRerunButton({ run }: { run: Pick<WorkflowRun, 'id' | 'status'> }) {
  const retry = useRetryExecution();
  const navigate = useNavigate();
  const t = useT();
  if (!['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(run.status)) {
    return null;
  }
  return (
    <Button
      variant="secondary" size="sm"
      icon={<RotateCcw className="w-4 h-4" />}
      loading={retry.isPending}
      onClick={() => retry.mutate(run.id, {
        onSuccess: next => navigate(`/workflows/${next.workflowId}/run/${next.id}`),
      })}
    >
      {t.workflows.execution.rerun}
    </Button>
  );
}
