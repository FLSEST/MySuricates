import type { RunStatus } from '../../types';

export function statusDot(status: RunStatus) {
  const colors: Record<string, string> = {
    COMPLETED: 'bg-success',
    COMPLETED_WITH_ERRORS: 'bg-warning',
    FAILED: 'bg-danger',
    INTERRUPTED: 'bg-danger',
    RUNNING: 'bg-accent',
    CANCELLED: 'bg-text-tertiary',
  };
  return <span className={`inline-block w-2 h-2 rounded-full ${colors[status] ?? 'bg-text-tertiary'}`} />;
}
