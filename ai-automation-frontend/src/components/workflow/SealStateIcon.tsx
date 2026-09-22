import { Lock, ShieldAlert } from 'lucide-react';
import { useT } from '../../i18n';
import type { WorkflowSealStatus } from '../../types';

/**
 * Lock for a sealed workflow — red shield when the seal is broken (its starts are refused). Falls
 * back to the plain lock while the seal status is still loading.
 */
export function SealStateIcon({ sealed, status, className = 'w-3 h-3' }: {
  sealed: boolean | undefined;
  status?: WorkflowSealStatus;
  className?: string;
}) {
  const t = useT();
  if (status?.state === 'VIOLATED') {
    return (
      <span title={t.workflows.sealView.violatedHint} className="shrink-0 inline-flex" data-testid="seal-violated">
        <ShieldAlert className={`${className} text-danger`} aria-label={t.workflows.sealView.violated} />
      </span>
    );
  }
  if (!sealed) return null;
  return (
    <span title={t.workflows.sealedHint} className="shrink-0 inline-flex" data-testid="seal-intact">
      <Lock className={`${className} text-accent`} aria-label={t.workflows.sealed} />
    </span>
  );
}
