import { Badge } from '../ui/Badge';
import type { ProjectStatus, RunStatus, ContainerStatus, StepStatus } from '../../types';

type AnyStatus = ProjectStatus | RunStatus | ContainerStatus | StepStatus | string;

const statusConfig: Record<string, { variant: 'success' | 'warning' | 'danger' | 'accent' | 'default'; label?: string }> = {
  // Project / Container
  RUNNING: { variant: 'success' },
  STARTING: { variant: 'warning' },
  STOPPED: { variant: 'default' },
  ERROR: { variant: 'danger' },
  CREATED: { variant: 'default' },
  // Workflow Run
  PENDING: { variant: 'default' },
  PAUSED: { variant: 'warning' },
  COMPLETED: { variant: 'success' },
  FAILED: { variant: 'danger' },
  CANCELLED: { variant: 'default' },
  // Step
  SKIPPED: { variant: 'default' },
};

interface StatusBadgeProps {
  status: AnyStatus;
  label?: string;
}

export function StatusBadge({ status, label }: StatusBadgeProps) {
  const config = statusConfig[status] ?? { variant: 'default' as const };
  return (
    <Badge variant={config.variant} dot>
      {label ?? config.label ?? status}
    </Badge>
  );
}
