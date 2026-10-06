import { ScrollText } from 'lucide-react';
import { Card, CardHeader, Select } from '../ui';
import { useUpdateWorkflow } from '../../hooks/useWorkflows';
import { useExecutionConfig } from '../../hooks/useSystem';
import { useT } from '../../i18n';
import type { LiveLogPersistChoice, Workflow } from '../../types';

/**
 * Whether a run's live log is kept after the run (`liveLogPersist`, TODO bbbfcbf8): system default,
 * always or never. An operating setting outside the definition, so it stays editable on sealed
 * workflows — render it outside the sidebar's locking fieldset.
 */
export function WorkflowLiveLogCard({ workflow }: { workflow: Workflow }) {
  const t = useT();
  const updateWorkflow = useUpdateWorkflow();
  const { data: config } = useExecutionConfig();
  const systemDefaultOn = (config?.liveLogPersistDefault ?? 1) === 1;

  return (
    <Card>
      <CardHeader title={t.workflows.liveLogPersist} />
      <Select
        value={workflow.liveLogPersist ?? 'default'}
        onChange={(e) =>
          updateWorkflow.mutate({ id: workflow.id, data: { liveLogPersist: e.target.value as LiveLogPersistChoice } })
        }
        options={[
          {
            value: 'default',
            label: systemDefaultOn ? t.workflows.liveLogPersistDefaultOn : t.workflows.liveLogPersistDefaultOff,
          },
          { value: 'on', label: t.workflows.liveLogPersistOn },
          { value: 'off', label: t.workflows.liveLogPersistOff },
        ]}
        aria-label={t.workflows.liveLogPersist}
      />
      <p className="text-xs text-text-tertiary mt-2 flex items-center gap-1.5">
        <ScrollText className="w-3.5 h-3.5 shrink-0" aria-hidden />
        {t.workflows.liveLogPersistHint}
      </p>
    </Card>
  );
}
