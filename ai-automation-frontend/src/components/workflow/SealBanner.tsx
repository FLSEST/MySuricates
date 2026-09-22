import { Lock, LockOpen, ShieldAlert, Ban } from 'lucide-react';
import { Button } from '../ui';
import { useT, useI18nStore } from '../../i18n';
import { useSealWorkflow } from '../../hooks/useWorkflows';
import { useModuleEnabled } from '../../hooks/useModules';
import { formatDateTime, formatRelativeTime } from '../../utils/formatTime';
import type { Workflow, WorkflowSealStatus } from '../../types';

/**
 * The seal, said where it applies: under the workflow header instead of only in tooltips. Intact →
 * what is locked and since when; broken → why its starts are refused and what to do; not sealed
 * but starts refused (unattended policy) → that they were refused, since they leave no run behind.
 */
export function SealBanner({ workflow, status }: { workflow: Workflow; status?: WorkflowSealStatus }) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const sealingModule = useModuleEnabled('sealing');
  const sealWorkflow = useSealWorkflow();
  const s = t.workflows.sealView;

  const refused = status && status.refusedStarts > 0 && status.lastRefusedAt ? (
    <p className="text-xs mt-1" data-testid="seal-refused">
      {s.refusedStarts
        .replace('{count}', String(status.refusedStarts))
        .replace('{when}', formatRelativeTime(status.lastRefusedAt, locale))}
      {status.lastRefusal && <span className="block text-text-tertiary truncate" title={status.lastRefusal}>{status.lastRefusal}</span>}
    </p>
  ) : null;

  const unsealButton = sealingModule ? (
    <Button variant="secondary" size="sm" icon={<LockOpen className="w-4 h-4" />}
            loading={sealWorkflow.isPending}
            onClick={() => { if (confirm(t.workflows.unsealConfirm)) sealWorkflow.mutate({ id: workflow.id, lock: false }); }}>
      {t.workflows.unseal}
    </Button>
  ) : null;

  if (status?.state === 'VIOLATED') {
    const reason = status.problem === 'SKILLS_CHANGED'
      ? s.problemSkills.replace('{skills}', status.changedSkills.map((k) => k.name ?? `${s.deletedSkill} (${k.id.slice(0, 8)})`).join(', '))
      : status.problem === 'DEFINITION_CHANGED' ? s.problemDefinition : s.problemUnverifiable;
    return (
      <div role="alert" data-testid="seal-banner" data-state="violated"
           className="flex items-start gap-3 p-3 rounded-lg border border-danger/40 bg-danger-subtle text-danger">
        <ShieldAlert className="w-5 h-5 shrink-0 mt-0.5" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-semibold">{s.violatedTitle}</p>
          <p className="text-sm text-text mt-0.5">{reason}</p>
          <p className="text-xs text-text-secondary mt-1">{s.violatedAction}</p>
          {refused}
        </div>
        {unsealButton}
      </div>
    );
  }

  if (workflow.sealed) {
    const pinned = status?.skillsPinned ?? true;
    const body = status && status.pinnedSkillCount > 0
      ? s.intactBody.replace('{count}', String(status.pinnedSkillCount))
      : s.intactBodyNoSkills;
    return (
      <div data-testid="seal-banner" data-state="intact"
           className="flex items-start gap-3 p-3 rounded-lg border border-border-strong bg-surface-raised">
        <Lock className="w-5 h-5 shrink-0 mt-0.5 text-accent" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-semibold text-text">
            {workflow.sealedAt ? s.intactTitle.replace('{date}', formatDateTime(workflow.sealedAt, locale)) : t.workflows.sealed}
          </p>
          <p className="text-sm text-text-secondary mt-0.5">{body}</p>
          {!pinned && <p className="text-xs text-warning mt-1" data-testid="seal-legacy">{s.legacyBody}</p>}
        </div>
        {!pinned && sealingModule && (
          <Button variant="secondary" size="sm" icon={<Lock className="w-4 h-4" />} loading={sealWorkflow.isPending}
                  onClick={() => sealWorkflow.mutate({ id: workflow.id, lock: true })}>
            {s.pinSkills}
          </Button>
        )}
        {unsealButton}
      </div>
    );
  }

  if (refused) {
    return (
      <div role="status" data-testid="seal-banner" data-state="refused"
           className="flex items-start gap-3 p-3 rounded-lg border border-warning/40 bg-warning-subtle text-warning">
        <Ban className="w-5 h-5 shrink-0 mt-0.5" />
        <div className="flex-1 min-w-0 text-text">
          <p className="text-sm font-semibold text-warning">{s.refusedTitle}</p>
          {refused}
        </div>
      </div>
    );
  }
  return null;
}
