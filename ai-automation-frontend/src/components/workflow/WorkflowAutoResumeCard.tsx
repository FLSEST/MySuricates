import { RotateCcw } from 'lucide-react';
import { Card, Toggle } from '../ui';
import { useUpdateWorkflow } from '../../hooks/useWorkflows';
import { useT } from '../../i18n';
import type { Workflow } from '../../types';

/**
 * Opt-in to resume a run automatically after a restart or standby (`metadata.autoResume`,
 * TODO 90d10ba1). The key is part of the definition, so a sealed workflow shows it read-only
 * through the surrounding fieldset. While on, a hint says what a resume repeats.
 */
export function WorkflowAutoResumeCard({ workflow }: { workflow: Workflow }) {
  const t = useT();
  const updateWorkflow = useUpdateWorkflow();
  const enabled = !!workflow.metadata?.autoResume?.enabled;

  return (
    <Card>
      <div className="flex items-center justify-between gap-3">
        <div>
          <span className="text-sm font-medium text-text">{t.workflows.autoResume}</span>
          <p className="text-xs text-text-tertiary">{t.workflows.autoResumeDescription}</p>
        </div>
        <Toggle
          enabled={enabled}
          onChange={(next) =>
            updateWorkflow.mutate({
              id: workflow.id,
              data: { metadata: { autoResume: { ...workflow.metadata?.autoResume, enabled: next } } },
            })
          }
          ariaLabel={t.workflows.autoResume}
        />
      </div>
      {enabled && (
        <p className="mt-3 flex items-start gap-2 text-xs text-warning">
          <RotateCcw className="w-3.5 h-3.5 mt-0.5 shrink-0" aria-hidden />
          <span>{t.workflows.autoResumeHint}</span>
        </p>
      )}
    </Card>
  );
}
