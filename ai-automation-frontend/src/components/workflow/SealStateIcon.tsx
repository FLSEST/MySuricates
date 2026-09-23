import { Lock, LockOpen, ShieldAlert } from 'lucide-react';
import { useT } from '../../i18n';
import type { WorkflowSealStatus } from '../../types';

type SealDisplayState = 'intact' | 'violated' | 'unsealed';

function sealDisplayState(sealed: boolean | null | undefined, status?: WorkflowSealStatus): SealDisplayState {
  if (status?.state === 'VIOLATED') return 'violated';
  return sealed ? 'intact' : 'unsealed';
}

/**
 * Seal state as an icon: lock when sealed, red shield when the seal is broken (its starts are
 * refused), open lock when not sealed — both states stay visible. Falls back to the plain lock while
 * the seal status is still loading.
 */
export function SealStateIcon({ sealed, status, className = 'w-3 h-3' }: {
  sealed: boolean | null | undefined;
  status?: WorkflowSealStatus;
  className?: string;
}) {
  const t = useT();
  const state = sealDisplayState(sealed, status);
  if (state === 'violated') {
    return (
      <span title={t.workflows.sealView.violatedHint} className="shrink-0 inline-flex" data-testid="seal-violated">
        <ShieldAlert className={`${className} text-danger`} aria-label={t.workflows.sealView.violated} />
      </span>
    );
  }
  if (state === 'unsealed') {
    return (
      <span title={t.workflows.sealView.notSealedHint} className="shrink-0 inline-flex" data-testid="seal-unsealed">
        <LockOpen className={`${className} text-text-tertiary`} aria-label={t.workflows.sealView.notSealed} />
      </span>
    );
  }
  return (
    <span title={t.workflows.sealedHint} className="shrink-0 inline-flex" data-testid="seal-intact">
      <Lock className={`${className} text-accent`} aria-label={t.workflows.sealed} />
    </span>
  );
}

/** Seal state as a labelled chip next to a workflow name — shown for every state, not only when sealed. */
export function SealBadge({ sealed, status }: { sealed: boolean | null | undefined; status?: WorkflowSealStatus }) {
  const t = useT();
  const state = sealDisplayState(sealed, status);
  const look = {
    violated: { cls: 'bg-danger-subtle text-danger', icon: ShieldAlert, label: t.workflows.sealView.violated, hint: t.workflows.sealView.violatedHint },
    intact: { cls: 'bg-accent/15 text-accent', icon: Lock, label: t.workflows.sealed, hint: t.workflows.sealedHint },
    unsealed: { cls: 'bg-surface-raised text-text-secondary border border-border', icon: LockOpen, label: t.workflows.sealView.notSealed, hint: t.workflows.sealView.notSealedHint },
  }[state];
  const Icon = look.icon;
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium shrink-0 ${look.cls}`}
          title={look.hint} data-testid="sealed-badge" data-state={state}>
      <Icon className="w-3 h-3" />
      {look.label}
    </span>
  );
}
