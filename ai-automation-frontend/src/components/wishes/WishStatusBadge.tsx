import { Badge } from '../ui';
import type { WishStatus } from '../../types';

export function WishStatusBadge({ status }: { status: WishStatus }) {
  const variant = status === 'COMPLETED' ? 'success'
    : status === 'FAILED' ? 'danger'
    : status === 'CANCELLED' ? 'warning'
    : (status === 'AWAITING_APPROVAL' || status === 'AWAITING_CLARIFICATION') ? 'warning'
    : status === 'AWAITING_DECOMPOSE_APPROVAL' ? 'accent'
    : (status === 'DECOMPOSING' || status === 'EXECUTING' || status === 'EVALUATING' || status === 'REFINING') ? 'accent'
    : 'default';
  const isActive = status === 'DECOMPOSING' || status === 'EXECUTING' || status === 'EVALUATING' || status === 'REFINING';
  const isAwaiting = status === 'AWAITING_APPROVAL' || status === 'AWAITING_CLARIFICATION' || status === 'AWAITING_DECOMPOSE_APPROVAL';
  return <Badge variant={variant} dot={isActive || isAwaiting}>{status}</Badge>;
}
