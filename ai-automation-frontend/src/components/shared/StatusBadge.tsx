import { Badge } from '../ui/Badge';
import { useStatusLabel } from './statusLabel';
import type { ProjectStatus, RunStatus, ContainerStatus, StepStatus } from '../../types';

type AnyStatus = ProjectStatus | RunStatus | ContainerStatus | StepStatus | string;
type Variant = 'success' | 'warning' | 'danger' | 'accent' | 'default';

const statusVariant: Record<string, Variant> = {
  // Project / Container
  RUNNING: 'success',
  STARTING: 'warning',
  STOPPED: 'default',
  ERROR: 'danger',
  CREATED: 'default',
  // Workflow Run
  PENDING: 'default',
  PAUSED: 'warning',
  COMPLETED: 'success',
  // A checked step failed and the run carried on: finished, but not clean.
  COMPLETED_WITH_ERRORS: 'warning',
  FAILED: 'danger',
  CANCELLED: 'default',
  INTERRUPTED: 'warning',
  // Step
  SKIPPED: 'default',
  WAITING_APPROVAL: 'warning',
  AWAITING_DECOMPOSE_APPROVAL: 'accent',
};

interface StatusBadgeProps {
  status: AnyStatus;
  label?: string;
}

export function StatusBadge({ status, label }: StatusBadgeProps) {
  const statusLabel = useStatusLabel();
  return (
    <Badge variant={statusVariant[status] ?? 'default'} dot>
      {label ?? statusLabel(status)}
    </Badge>
  );
}
