import { Lock, LockOpen, Minus, ShieldAlert } from 'lucide-react';
import { useT } from '../../i18n';
import type { WorkflowSealStatus } from '../../types';

type SealDisplayState = 'intact' | 'violated' | 'unsealed';

function sealDisplayState(sealed: boolean | null | undefined, status?: WorkflowSealStatus): SealDisplayState {
  if (status?.state === 'VIOLATED') return 'violated';
  return sealed ? 'intact' : 'unsealed';
}

/**
 * Seal state as a small marker. The two normal states differ by more than the lock's shackle, which
 * is barely visible at this size: sealed is a filled accent chip, not sealed a bare grey open lock,
 * a broken seal a red chip. `unrecorded` is for runs started before runs recorded their seal state —
 * shown as a dash rather than guessed from the workflow's state today.
 */
export function SealStateIcon({ sealed, status, unrecorded = false }: {
  sealed: boolean | null | undefined;
  status?: WorkflowSealStatus;
  unrecorded?: boolean;
}) {
  const t = useT();
  const s = t.workflows.sealView;
  if (unrecorded) {
    return (
      <span title={s.unrecordedHint} className="shrink-0 inline-flex p-0.5" data-testid="seal-unrecorded">
        <Minus className="w-3.5 h-3.5 text-text-tertiary/60" aria-label={s.unrecorded} />
      </span>
    );
  }
  const state = sealDisplayState(sealed, status);
  if (state === 'violated') {
    return (
      <span title={s.violatedHint} className="shrink-0 inline-flex p-0.5 rounded bg-danger-emphasis text-text-inverse" data-testid="seal-violated">
        <ShieldAlert className="w-3 h-3" aria-label={s.violated} />
      </span>
    );
  }
  if (state === 'unsealed') {
    return (
      <span title={s.notSealedHint} className="shrink-0 inline-flex p-0.5" data-testid="seal-unsealed">
        <LockOpen className="w-3.5 h-3.5 text-text-tertiary" aria-label={s.notSealed} />
      </span>
    );
  }
  return (
    <span title={t.workflows.sealedHint} className="shrink-0 inline-flex p-0.5 rounded bg-accent text-text-inverse" data-testid="seal-intact">
      <Lock className="w-3 h-3" aria-label={t.workflows.sealed} />
    </span>
  );
}

/** Seal state as a labelled chip next to a workflow name — shown for every state, not only when sealed. */
export function SealBadge({ sealed, status }: { sealed: boolean | null | undefined; status?: WorkflowSealStatus }) {
  const t = useT();
  const state = sealDisplayState(sealed, status);
  const look = {
    violated: { cls: 'bg-danger-emphasis text-text-inverse', icon: ShieldAlert, label: t.workflows.sealView.violated, hint: t.workflows.sealView.violatedHint },
    intact: { cls: 'bg-accent text-text-inverse', icon: Lock, label: t.workflows.sealed, hint: t.workflows.sealedHint },
    unsealed: { cls: 'text-text-secondary border border-dashed border-border-strong', icon: LockOpen, label: t.workflows.sealView.notSealed, hint: t.workflows.sealView.notSealedHint },
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
