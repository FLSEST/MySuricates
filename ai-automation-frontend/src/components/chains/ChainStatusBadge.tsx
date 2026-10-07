import { Badge } from '../ui';
import { useStatusLabel } from '../shared/statusLabel';

export function ChainStatusBadge({ status }: { status: string }) {
  const statusLabel = useStatusLabel();
  const variant = status === 'COMPLETED' ? 'success'
    : status === 'COMPLETED_WITH_ERRORS' ? 'warning'
    : status === 'FAILED' ? 'danger'
    : status === 'RUNNING' ? 'accent'
    : status === 'CANCELLED' ? 'warning'
    : 'default';
  return <Badge variant={variant} dot>{statusLabel(status)}</Badge>;
}
