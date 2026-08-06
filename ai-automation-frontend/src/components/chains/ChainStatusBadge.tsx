import { Badge } from '../ui';

export function ChainStatusBadge({ status }: { status: string }) {
  const variant = (status === 'COMPLETED' || status === 'COMPLETED_WITH_ERRORS') ? 'success'
    : status === 'FAILED' ? 'danger'
    : status === 'RUNNING' ? 'accent'
    : status === 'CANCELLED' ? 'warning'
    : 'default';
  const label = status === 'COMPLETED_WITH_ERRORS' ? 'Completed' : status;
  return <Badge variant={variant} dot>{label}</Badge>;
}
