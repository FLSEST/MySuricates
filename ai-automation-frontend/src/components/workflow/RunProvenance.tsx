import { Lock, LockOpen, Minus } from 'lucide-react';
import { CopyButton } from '../shared/CopyButton';
import { useT } from '../../i18n';
import type { WorkflowRun } from '../../types';

/**
 * Herkunft eines Runs: Siegel-Zustand, was ihn gestartet hat und welche Definition er ausführte.
 * Immer sichtbar — der Siegel-Chip deckt jeden Zustand ab, auch Runs von vor der Aufzeichnung.
 */
export function RunProvenance({ run }: { run: WorkflowRun }) {
  const t = useT();
  return (
    <div className="flex flex-wrap items-center gap-2 text-xs" data-testid="run-provenance">
      {run.sealed === true && (
        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-accent text-text-inverse font-medium"
              data-testid="run-sealed"
              title={Object.keys(run.sealedSkillPins ?? {}).length > 0
                ? t.workflows.sealView.runSealedSkills.replace('{count}', String(Object.keys(run.sealedSkillPins ?? {}).length))
                : t.workflows.sealView.runSealedNoSkills}>
          <Lock className="w-3 h-3" />
          {t.workflows.sealView.runSealed}
        </span>
      )}
      {run.sealed === false && (
        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full border border-dashed border-border-strong text-text-secondary"
              data-testid="run-not-sealed">
          <LockOpen className="w-3 h-3" />
          {t.workflows.sealView.runNotSealed}
        </span>
      )}
      {run.sealed == null && (
        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-surface-raised text-text-tertiary"
              title={t.workflows.sealView.unrecordedHint} data-testid="run-seal-unrecorded">
          <Minus className="w-3 h-3" />
          {t.workflows.sealView.unrecorded}
        </span>
      )}
      {run.triggerSource && (
        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-surface-raised">
          <span className="text-text-secondary">{t.workflows.triggeredBy}</span>
          <span className="text-text font-medium">{t.workflows.triggerSources[run.triggerSource]}</span>
        </span>
      )}
      {run.definitionHash && (
        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-surface-raised font-mono"
              title={t.workflows.definitionHashHint}>
          <span className="text-text-secondary">{t.workflows.definitionHash}</span>
          <span className="text-text">{run.definitionHash.slice(0, 12)}</span>
          <CopyButton text={run.definitionHash} />
        </span>
      )}
    </div>
  );
}
